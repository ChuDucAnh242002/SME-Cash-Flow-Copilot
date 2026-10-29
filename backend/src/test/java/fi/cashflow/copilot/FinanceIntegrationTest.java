package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties = {"app.password=integration-test-password", "spring.datasource.password=test",
        "app.outbox-delay-ms=100", "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"})
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 3, topics = "cashflow.finance-changed.v1")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext
class FinanceIntegrationTest {
    @Autowired FinanceService service;
    @Autowired FinanceStore store;
    @Autowired EventPipeline events;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;

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
            store.jdbc().update("DELETE FROM document");
            store.jdbc().update("DELETE FROM bank_transaction");
            store.jdbc().update("DELETE FROM account");
            store.jdbc().update("DELETE FROM outbox");
            store.jdbc().update("DELETE FROM processed_event");
            store.jdbc().update("UPDATE workspace SET planning_balance=NULL,last_sync=NULL,sync_error=NULL,last_reconciled=NULL");
        });
    }

    @Test
    void persistsDeduplicatesReconcilesAndForecastsThroughKafka() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/bank/sync").with(httpBasic("developer", "integration-test-password")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/dashboard?days=999").with(httpBasic("developer", "integration-test-password")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/documents").with(httpBasic("developer", "integration-test-password")).with(csrf())
                        .contentType("application/json").content("""
                                {"kind":"INVOICE","counterparty":"Invalid","reference":"invalid","amount":-1,"dueDate":"2026-10-29"}
                                """)).andExpect(status().isBadRequest());
        service.seedDemo();
        assertThat(service.sync().imported()).isEqualTo(3);
        assertThat(service.sync().imported()).isZero();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var dashboard = service.dashboard(30, 0);
            assertThat(dashboard.status().pendingEvents()).isZero();
            assertThat(dashboard.documents().stream().filter(doc -> doc.paidAmount().signum() > 0).count()).isEqualTo(2);
        });
        var dashboard = service.dashboard(30, 0);
        assertThat(dashboard.account().bookedBalance()).isEqualByComparingTo("12500.00");
        assertThat(dashboard.forecast().minimumBalance()).isEqualByComparingTo("-2100.00");
        assertThat(dashboard.forecast().firstShortfall()).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(dashboard.forecast().series().getLast().balance()).isEqualByComparingTo("8800.00");
        assertThat(dashboard.transactions()).hasSize(3);
        assertThat(dashboard.transactions().stream().filter(t -> t.documentId() == null).count()).isEqualTo(1);

        assertThatThrownBy(() -> service.create(new DocumentInput(Kind.INVOICE, "Duplicate", "10 001",
                new BigDecimal("100"), LocalDate.of(2026, 10, 29)))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(store.documents()).hasSize(6);

        assertThatThrownBy(() -> service.createBatch(List.of(
                new DocumentInput(Kind.EXPENSE, "Atomic batch", "BATCH-ROLLBACK", new BigDecimal("10"),
                        LocalDate.of(2026, 11, 1)),
                new DocumentInput(Kind.INVOICE, "Duplicate", "10001", new BigDecimal("100"),
                        LocalDate.of(2026, 11, 1))))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(store.documents()).hasSize(6);
        var failedBankService = new FinanceService(store,
                () -> { throw new BankAccessException("Sandbox token expired."); },
                new ForecastEngine(), tx, Clock.systemUTC(), "demo");
        assertThatThrownBy(failedBankService::sync).hasMessage("Sandbox token expired.");
        assertThat(service.dashboard(30, 0).status().syncError()).isEqualTo("Sandbox token expired.");
        assertThat(service.dashboard(30, 0).account().bookedBalance()).isEqualByComparingTo("12500.00");
        service.sync();
        assertThat(service.dashboard(30, 0).status().syncError()).isNull();

        UUID id = UUID.randomUUID();
        String payload = "{\"id\":\"" + id + "\",\"type\":\"BANK_SYNCED\",\"schemaVersion\":1}";
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<>("test", 0, 0L, "workspace-1", payload);
        events.consume(record);
        events.consume(record);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM processed_event WHERE id=?", Integer.class, id)).isEqualTo(1);
        assertThat(service.dashboard(30, 0).forecast().minimumBalance()).isEqualByComparingTo("-2100.00");

        service.create(new DocumentInput(Kind.EXPENSE, "Partial subscription", "PARTIAL", new BigDecimal("100"),
                LocalDate.of(2026, 11, 1)));
        tx.executeWithoutResult(status -> {
            store.lock();
            store.jdbc().update("""
                    INSERT INTO bank_transaction VALUES ('DEMO-FI-EUR','partial-1','2026-10-28',-40,'PARTIAL','Supplier')
                    """);
            store.jdbc().update("""
                    INSERT INTO bank_transaction VALUES ('DEMO-FI-EUR','excess-1','2026-10-29',-70,'PARTIAL','Supplier')
                    """);
            store.reconcile();
        });
        var partial = store.documents().stream().filter(doc -> doc.reference().equals("PARTIAL")).findFirst().orElseThrow();
        assertThat(partial.paidAmount()).isEqualByComparingTo("40");
        assertThat(partial.remaining()).isEqualByComparingTo("60");
        assertThat(store.transactions().stream().filter(t -> t.externalId().equals("excess-1")).findFirst().orElseThrow().documentId()).isNull();
    }

    @Test
    void planningBalanceAndDocumentLifecyclePreserveBankFactsAndRejectStaleWrites() throws Exception {
        var auth = httpBasic("developer", "integration-test-password");
        mvc.perform(put("/api/planning").with(auth).contentType("application/json")
                .content("{\"openingBalance\":12000}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/planning").with(auth).with(csrf()).contentType("application/json")
                .content("{\"openingBalance\":0.001}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/planning").with(auth).with(csrf()).contentType("application/json")
                .content("{\"openingBalance\":12000}")).andExpect(status().isNoContent());
        assertThat(service.dashboard(30, 0).account()).isNull();
        assertThat(service.dashboard(30, 0).forecast().openingBalance()).isEqualByComparingTo("12000");
        service.sync();
        service.seedDemo();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(service.dashboard(30, 0).status().pendingEvents()).isZero());
        var dashboard = service.dashboard(30, 0);
        assertThat(dashboard.planningBalance()).isEqualByComparingTo("12000");
        assertThat(dashboard.account().bookedBalance()).isEqualByComparingTo("12500");
        assertThat(dashboard.forecast().minimumBalance()).isEqualByComparingTo("-2600");
        assertThat(store.planningBalance()).isEqualByComparingTo("12000");

        var equipment = findDocument("20003");
        var delayed = new DocumentInput(equipment.kind(), equipment.counterparty(), equipment.reference(),
                equipment.amount(), equipment.dueDate(), LocalDate.of(2026, 11, 18));
        mvc.perform(put("/api/documents/" + equipment.id()).with(auth).with(csrf())
                .contentType("application/json").content("""
                {"revision":0,"document":{"kind":"EXPENSE","counterparty":"Equipment supplier","reference":"20003",
                  "amount":4800,"dueDate":"2026-11-07","expectedDate":"2026-11-18"}}
                """)).andExpect(status().isNoContent());
        assertThat(findDocument("20003").expectedDate()).isEqualTo(LocalDate.of(2026, 11, 18));
        assertThat(findDocument("20003").dueDate()).isEqualTo(equipment.dueDate());
        assertThat(service.dashboard(30, 0).forecast().minimumBalance()).isEqualByComparingTo("2200");
        assertThatThrownBy(() -> service.update(equipment.id(), new DocumentUpdate(delayed, 0L)))
                .hasMessageContaining("another session");
        mvc.perform(put("/api/documents/" + equipment.id()).with(auth).with(csrf())
                .contentType("application/json").content("{\"revision\":1,\"document\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/documents/" + UUID.randomUUID() + "/cancel").with(auth).with(csrf())
                .contentType("application/json").content("{\"revision\":0}")).andExpect(status().isNotFound());

        service.cancel(equipment.id(), new RevisionInput(1L));
        assertThat(findDocument("20003").cancelled()).isTrue();
        assertThat(store.documents()).hasSize(6);
        assertThat(service.dashboard(30, 0).forecast().series().getLast().balance()).isEqualByComparingTo("13100");
        assertThatThrownBy(() -> service.update(equipment.id(), new DocumentUpdate(delayed, 2L)))
                .hasMessageContaining("Cancelled");
        assertThatThrownBy(() -> service.create(new DocumentInput(Kind.EXPENSE, "Reuse", "20003",
                new BigDecimal("1"), LocalDate.of(2026, 11, 1)))).isInstanceOf(DataIntegrityViolationException.class);
        var matched = findDocument("10001");
        assertThatThrownBy(() -> service.cancel(matched.id(), new RevisionInput(0L))).hasMessageContaining("matched payments");
        assertThatThrownBy(() -> service.update(matched.id(), new DocumentUpdate(
                new DocumentInput(Kind.INVOICE, matched.counterparty(), "CHANGED", matched.amount(), matched.dueDate()), 0L)))
                .hasMessageContaining("payment reference cannot");
        assertThatThrownBy(() -> service.update(matched.id(), new DocumentUpdate(
                new DocumentInput(Kind.INVOICE, matched.counterparty(), matched.reference(), new BigDecimal("1"), matched.dueDate()), 0L)))
                .hasMessageContaining("lower than");
        assertThat(findDocument("10001").paidAmount()).isEqualByComparingTo("3200");
        assertThat(service.dashboard(30, 0).account().bookedBalance()).isEqualByComparingTo("12500");
        mvc.perform(put("/api/planning").with(auth).with(csrf()).contentType("application/json")
                .content("{\"openingBalance\":null}")).andExpect(status().isNoContent());
        assertThat(service.dashboard(30, 0).planningBalance()).isNull();
        assertThat(service.dashboard(30, 0).forecast().openingBalance()).isEqualByComparingTo("12500");
        service.setPlanningBalance(new PlanningInput(BigDecimal.ZERO));
        assertThat(service.dashboard(30, 0).forecast().openingBalance()).isEqualByComparingTo("0");
        service.setPlanningBalance(new PlanningInput(new BigDecimal("-0.01")));
        assertThat(service.dashboard(30, 0).forecast().firstShortfall()).isEqualTo(LocalDate.of(2026, 10, 29));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(service.dashboard(30, 0).status().pendingEvents()).isZero());
        assertThat(findDocument("20003").cancelled()).isTrue();
    }

    @Test
    void lifecycleGuardsReconcileUnprocessedPaymentsAndSkipCancelledReferences() {
        service.sync();
        service.seedDemo();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(service.dashboard(30, 0).status().pendingEvents()).isZero());
        var equipment = findDocument("20003");
        service.cancel(equipment.id(), new RevisionInput(0L));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(service.dashboard(30, 0).status().pendingEvents()).isZero());

        // Persist bank facts without delivering an event, modelling a lagging consumer.
        tx.executeWithoutResult(status -> {
            store.lock();
            store.jdbc().update("""
                    INSERT INTO bank_transaction VALUES
                    ('DEMO-FI-EUR','late-equipment','2026-10-29',-100,'20003','Equipment supplier'),
                    ('DEMO-FI-EUR','queued-salary','2026-10-29',-40,'20002','Team salaries')
                    """);
        });
        var salaries = findDocument("20002");
        assertThat(salaries.paidAmount()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> service.cancel(salaries.id(), new RevisionInput(0L)))
                .hasMessageContaining("matched payments");
        assertThat(findDocument("20002").cancelled()).isFalse();
        service.update(salaries.id(), new DocumentUpdate(new DocumentInput(salaries.kind(),
                salaries.counterparty(), salaries.reference(), salaries.amount(), salaries.dueDate(),
                salaries.dueDate().plusDays(1)), 0L));
        assertThat(findDocument("20002").paidAmount()).isEqualByComparingTo("40");
        assertThat(store.transactions().stream().filter(transaction -> transaction.externalId().equals("late-equipment"))
                .findFirst().orElseThrow().documentId()).isNull();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(service.dashboard(30, 0).status().pendingEvents()).isZero());
        assertThat(findDocument("20003").cancelled()).isTrue();
    }

    Document findDocument(String reference) {
        return store.documents().stream().filter(document -> document.reference().equals(reference)).findFirst().orElseThrow();
    }
}
