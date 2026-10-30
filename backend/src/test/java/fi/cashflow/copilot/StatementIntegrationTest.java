package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties = {"app.bank-mode=statement", "app.password=integration-test-password",
        "spring.datasource.password=test", "app.outbox-delay-ms=100",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"})
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 3, topics = "cashflow.finance-changed.v1")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext
class StatementIntegrationTest {
    @Autowired FinanceService service;
    @Autowired FinanceStore store;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    static final String ROWS = "receipt,2026-10-28,400,INV-1,Customer\n"
            + "rent,2026-10-29,-200,RENT,Landlord\nsubscription,2026-10-29,-89,,Software\n";

    @TestConfiguration
    static class TestClock {
        @Bean @ServiceConnection PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:17-alpine");
        }
        @Bean @Primary Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneId.of("Europe/Helsinki"));
        }
    }

    @BeforeEach
    void clearWorkspace() {
        tx.executeWithoutResult(status -> {
            store.lock();
            store.jdbc().update("DELETE FROM allocation");
            store.jdbc().update("DELETE FROM payment_review_audit");
            store.jdbc().update("DELETE FROM payment_review");
            store.jdbc().update("DELETE FROM document");
            store.jdbc().update("DELETE FROM recurring_expense");
            store.jdbc().update("DELETE FROM bank_transaction");
            store.jdbc().update("DELETE FROM account");
            store.jdbc().update("DELETE FROM outbox");
            store.jdbc().update("DELETE FROM processed_event");
            store.jdbc().update("""
                    UPDATE workspace SET planning_balance=NULL,last_sync=NULL,sync_error=NULL,last_reconciled=NULL,
                    statement_date=NULL,statement_revision=0,recurrence_error=NULL
                    """);
        });
    }

    MockHttpServletRequestBuilder upload(String operation, String rows, String balance, String closingDate) {
        return multipart("/api/statements/" + operation)
                .file(new MockMultipartFile("file", "statement.csv", "text/csv",
                        (StatementCsvImportTest.HEADER + rows).getBytes(StandardCharsets.UTF_8)))
                .param("accountId", "STATEMENT-EUR").param("accountName", "Statement Company")
                .param("closingBalance", balance).param("closingDate", closingDate);
    }

    StatementPreview preview(String rows, String balance, String closingDate) throws Exception {
        String json = mvc.perform(upload("preview", rows, balance, closingDate)
                .with(httpBasic("developer", "integration-test-password")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readValue(json, StatementPreview.class);
    }

    @Test
    void previewsWithoutWritesImportsOnceAndReconcilesUnpaidPortionsWithoutDoubleCounting() throws Exception {
        service.create(new DocumentInput(Kind.INVOICE, "Customer", "INV-1", new BigDecimal("1000"),
                LocalDate.of(2026, 11, 3)));
        service.create(new DocumentInput(Kind.EXPENSE, "Landlord", "RENT", new BigDecimal("200"),
                LocalDate.of(2026, 10, 29)));
        var preview = preview(ROWS, "5000", "2026-10-29");
        assertThat(preview.newCount()).isEqualTo(3);
        assertThat(preview.duplicateCount()).isZero();
        assertThat(preview.revision()).isZero();
        assertThat(store.account()).isNull();
        assertThat(store.transactions()).isEmpty();
        long eventsBefore = store.jdbc().queryForObject("SELECT count(*) FROM outbox", Long.class);
        mvc.perform(upload("import", ROWS, "5000", "2026-10-29").param("revision", "0")
                .with(httpBasic("developer", "integration-test-password")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(3));
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM outbox", Long.class)).isEqualTo(eventsBefore + 1);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var dashboard = service.dashboard(30, 0);
            assertThat(dashboard.status().pendingEvents()).isZero();
            assertThat(dashboard.transactions().stream().filter(t -> t.documentId() != null).count()).isEqualTo(2);
        });
        var dashboard = service.dashboard(30, 0);
        assertThat(dashboard.status().mode()).isEqualTo("statement");
        assertThat(dashboard.status().statementDate()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(dashboard.account().bookedBalance()).isEqualByComparingTo("5000");
        assertThat(dashboard.forecast().openingBalance()).isEqualByComparingTo("5000");
        assertThat(dashboard.forecast().series().getLast().balance()).isEqualByComparingTo("5600");
        assertThat(dashboard.documents().stream().filter(d -> d.reference().equals("INV-1")).findFirst().orElseThrow().paidAmount())
                .isEqualByComparingTo("400");

        var repeated = preview(ROWS, "5000.00", "2026-10-29");
        assertThat(repeated.newCount()).isZero();
        assertThat(repeated.duplicateCount()).isEqualTo(3);
        mvc.perform(upload("import", ROWS, "5000.00", "2026-10-29").param("revision", "1")
                .with(httpBasic("developer", "integration-test-password")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(0));
        assertThat(store.transactions()).hasSize(3);
        service.setPlanningBalance(new PlanningInput(new BigDecimal("12000")));
        mvc.perform(upload("import", ROWS + "second,2026-10-29,100,,Another\n", "5000", "2026-10-29")
                .param("revision", "2").with(httpBasic("developer", "integration-test-password")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(1));
        assertThat(store.transactions()).hasSize(4);
        assertThat(service.dashboard(30, 0).forecast().openingBalance()).isEqualByComparingTo("12000");
        mvc.perform(upload("import", ROWS + "second,2026-10-29,100,,Another\n", "5100", "2026-10-30")
                .param("revision", "3").with(httpBasic("developer", "integration-test-password")).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(store.account().bookedBalance()).isEqualByComparingTo("5000");
        assertThat(store.planningBalance()).isEqualByComparingTo("12000");
    }

    @Test
    void newerStatementUpdatesBalanceRetainsHistoryAndPreservesZeroPlanningOverride() throws Exception {
        String oldRows = ROWS.replace("2026-10-29", "2026-10-28");
        mvc.perform(upload("import", oldRows, "5000", "2026-10-28").param("revision", "0")
                .with(httpBasic("developer", "integration-test-password")).with(csrf())).andExpect(status().isOk());
        service.setPlanningBalance(new PlanningInput(BigDecimal.ZERO));
        String overlap = "receipt,2026-10-28,400,INV-1,Customer\n"
                + "new-receipt,2026-10-29,100,INV-2,New Customer";
        var preview = preview(overlap, "5100", "2026-10-29");
        assertThat(preview.newCount()).isEqualTo(1);
        assertThat(preview.duplicateCount()).isEqualTo(1);
        mvc.perform(upload("import", overlap, "5100", "2026-10-29").param("revision", "1")
                .with(httpBasic("developer", "integration-test-password")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(1));
        var dashboard = service.dashboard(30, 0);
        assertThat(dashboard.account().bookedBalance()).isEqualByComparingTo("5100");
        assertThat(dashboard.status().statementDate()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(dashboard.transactions()).hasSize(4);
        assertThat(dashboard.planningBalance()).isEqualByComparingTo("0");
        assertThat(dashboard.forecast().openingBalance()).isEqualByComparingTo("0");
        service.setPlanningBalance(new PlanningInput(null));
        assertThat(service.dashboard(30, 0).forecast().openingBalance()).isEqualByComparingTo("5100");
    }

    @Test
    void rejectsConflictsStaleConfirmationAndWrongAccountAtomically() throws Exception {
        mvc.perform(upload("import", ROWS, "5000", "2026-10-28").param("revision", "0")
                .with(httpBasic("developer", "integration-test-password")).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(store.account()).isNull();
        mvc.perform(upload("import", ROWS, "5000", "2026-10-29").param("revision", "0")
                .with(httpBasic("developer", "integration-test-password")).with(csrf())).andExpect(status().isOk());
        var original = store.transactions();
        for (String changed : new String[]{ROWS.replace("400", "401"), ROWS.replace("Customer", "Changed"),
                ROWS.replace("INV-1", "INV-2"), ROWS.replace("2026-10-28", "2026-10-27")}) {
            mvc.perform(upload("preview", "new,2026-10-29,10,,New\n" + changed, "5000", "2026-10-29")
                    .with(httpBasic("developer", "integration-test-password")).with(csrf()))
                    .andExpect(status().isConflict());
            mvc.perform(upload("import", "new,2026-10-29,10,,New\n" + changed, "5000", "2026-10-29")
                    .param("revision", "1").with(httpBasic("developer", "integration-test-password")).with(csrf()))
                    .andExpect(status().isConflict());
        }
        mvc.perform(upload("import", ROWS, "5000", "2026-10-29").param("revision", "0")
                .with(httpBasic("developer", "integration-test-password")).with(csrf())).andExpect(status().isConflict());
        mvc.perform(upload("preview", ROWS, "5001", "2026-10-29")
                .with(httpBasic("developer", "integration-test-password")).with(csrf())).andExpect(status().isConflict());
        mvc.perform(upload("preview", "old,2026-10-27,10,,Old", "4000", "2026-10-28")
                .with(httpBasic("developer", "integration-test-password")).with(csrf())).andExpect(status().isConflict());
        var other = new StatementInput("OTHER-EUR", "Other", new BigDecimal("5000"), LocalDate.of(2026, 10, 29));
        var importer = new StatementImportService(store, tx, Clock.systemUTC(), "statement");
        assertThatThrownBy(() -> importer.preview(other, java.util.List.of())).hasMessageContaining("another account");
        assertThat(store.transactions()).containsExactlyElementsOf(original);
        assertThat(store.account().bookedBalance()).isEqualByComparingTo("5000");
        assertThat(store.jdbc().queryForObject("SELECT statement_revision FROM workspace WHERE id=1", Long.class)).isEqualTo(1);
    }

    @Test
    void enforcesAuthenticationCsrfMetadataValidationAndSourceIsolation() throws Exception {
        mvc.perform(upload("preview", ROWS, "5000", "2026-10-29")).andExpect(status().isForbidden());
        mvc.perform(upload("preview", ROWS, "5000", "2026-10-29").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(upload("preview", ROWS, "5000", "2026-10-29")
                .with(httpBasic("developer", "integration-test-password"))).andExpect(status().isForbidden());
        mvc.perform(upload("preview", ROWS, "5000.001", "2026-10-29")
                .with(httpBasic("developer", "integration-test-password")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(upload("preview", ROWS, "5000", "not-a-date")
                .with(httpBasic("developer", "integration-test-password")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(upload("import", ROWS, "5000", "2026-10-29").param("revision", "-1")
                .with(httpBasic("developer", "integration-test-password")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(upload("import", ROWS, "5000", "2026-10-29")
                .with(httpBasic("developer", "integration-test-password")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/bank/sync").with(httpBasic("developer", "integration-test-password")).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/demo/seed").with(httpBasic("developer", "integration-test-password")).with(csrf()))
                .andExpect(status().isConflict());
        var nordeaImporter = new StatementImportService(store, tx, Clock.systemUTC(), "nordea");
        assertThatThrownBy(() -> nordeaImporter.preview(new StatementInput("ID", "Company", BigDecimal.ZERO,
                LocalDate.of(2026, 10, 29)), java.util.List.of())).hasMessageContaining("separate statement-mode");
        assertThat(store.account()).isNull();
        assertThat(store.transactions()).isEmpty();
    }
}
