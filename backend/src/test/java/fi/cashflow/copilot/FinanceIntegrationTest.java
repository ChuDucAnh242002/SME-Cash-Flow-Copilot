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
}
