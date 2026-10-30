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
    @Autowired PaymentReviewService reviews;
    @Autowired RecurringExpenseService recurring;

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
            store.jdbc().update("UPDATE workspace SET planning_balance=NULL,last_sync=NULL,sync_error=NULL,last_reconciled=NULL,recurrence_error=NULL");
        });
    }

    @Test
    void persistsDeduplicatesReconcilesAndForecastsThroughKafka() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/bank/sync").with(httpBasic("developer", "integration-test-password")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/dashboard?days=999").with(httpBasic("developer", "integration-test-password")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/statements/preview")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "statement.csv", "text/csv",
                        ("transactionId,bookingDate,amount,reference,counterparty\n"
                                + "receipt,2026-10-28,10,,Company").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .param("accountId", "CSV-EUR").param("accountName", "Company")
                .param("closingBalance", "100").param("closingDate", "2026-10-29")
                .with(httpBasic("developer", "integration-test-password")).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(store.account()).isNull();
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

    @Test
    void manualLinksAreAuditedAndSurviveSyncKafkaReplayAndDocumentGuards() throws Exception {
        service.seedDemo();
        service.sync();
        service.create(new DocumentInput(Kind.EXPENSE, "Software", "MANUAL-1", new BigDecimal("100"),
                LocalDate.of(2026, 11, 1)));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(service.dashboard(30, 0).status().pendingEvents()).isZero());
        var software = payment("demo-unmatched-1");
        assertThat(software.explanation()).isEqualTo(MatchExplanation.MISSING_REFERENCE);
        var document = findDocument("MANUAL-1");
        String decision = """
                {"accountId":"DEMO-FI-EUR","transactionId":"demo-unmatched-1","action":"LINK",
                 "documentId":"%s","revision":%d,"reason":"Verified supplier statement"}
                """.formatted(document.id(), software.reviewRevision());
        mvc.perform(post("/api/reconciliation/decisions").with(httpBasic("developer", "integration-test-password"))
                .with(csrf()).contentType("application/json").content(decision)).andExpect(status().isNoContent());
        assertThat(findDocument("MANUAL-1").paidAmount()).isEqualByComparingTo("89");
        assertThat(service.dashboard(30, 0).forecast().series().getLast().balance()).isEqualByComparingTo("8789");
        assertThat(service.dashboard(30, 0).account().bookedBalance()).isEqualByComparingTo("12500");
        var linked = payment("demo-unmatched-1");
        assertThat(linked.matchMode()).isEqualTo(MatchMode.MANUAL);
        assertThat(linked.explanation()).isEqualTo(MatchExplanation.MATCHED_MANUALLY);
        assertThat(linked.reviewRevision()).isGreaterThan(software.reviewRevision());
        mvc.perform(post("/api/reconciliation/decisions").with(httpBasic("developer", "integration-test-password"))
                .with(csrf()).contentType("application/json").content(decision)).andExpect(status().isConflict());
        var audit = reviews.history("DEMO-FI-EUR", "demo-unmatched-1");
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().actor()).isEqualTo("developer");
        assertThat(audit.getFirst().reason()).isEqualTo("Verified supplier statement");
        assertThat(audit.getFirst().nextDocumentLabel()).isEqualTo("Software / MANUAL-1");
        assertThat(audit.getFirst().previousDocumentId()).isNull();
        assertThat(audit.getFirst().occurredAt()).isNotNull();
        service.sync();
        String payload = "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"PAYMENT_REVIEWED\",\"schemaVersion\":1}";
        var event = new org.apache.kafka.clients.consumer.ConsumerRecord<>("test", 0, 0L, "workspace-1", payload);
        events.consume(event);
        events.consume(event);
        assertThat(payment("demo-unmatched-1").documentId()).isEqualTo(document.id());
        assertThat(payment("demo-unmatched-1").reviewRevision()).isEqualTo(linked.reviewRevision());
        assertThat(reviews.history("DEMO-FI-EUR", "demo-unmatched-1")).hasSize(1);
        assertThatThrownBy(() -> service.cancel(document.id(), new RevisionInput(document.revision())))
                .hasMessageContaining("matched payments");
        assertThatThrownBy(() -> service.update(document.id(), new DocumentUpdate(
                new DocumentInput(Kind.EXPENSE, "Software", "MANUAL-1", new BigDecimal("88"), document.dueDate()), document.revision())))
                .hasMessageContaining("lower than");
        mvc.perform(get("/api/reconciliation/history").param("accountId", "DEMO-FI-EUR")
                .param("transactionId", "demo-unmatched-1").with(httpBasic("developer", "integration-test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].actor").value("developer"));
        service.update(document.id(), new DocumentUpdate(new DocumentInput(Kind.EXPENSE,
                "Renamed supplier", "MANUAL-1", document.amount(), document.dueDate()), document.revision()));
        assertThat(reviews.history("DEMO-FI-EUR", "demo-unmatched-1").getFirst().nextDocumentLabel())
                .isEqualTo("Software / MANUAL-1");
        assertThat(findDocument("MANUAL-1").paidAmount()).isEqualByComparingTo("89");
    }

    @Test
    void unlinkHoldsPaymentUnmatchedUntilExplicitResumeAndRejectsConcurrentDecisions() throws Exception {
        service.seedDemo();
        service.sync();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(service.dashboard(30, 0).status().pendingEvents()).isZero());
        var receipt = payment("demo-receipt-1");
        assertThat(receipt.explanation()).isEqualTo(MatchExplanation.MATCHED_AUTOMATICALLY);
        var unlink = new PaymentReviewInput("DEMO-FI-EUR", receipt.externalId(), ReviewAction.UNLINK,
                null, receipt.reviewRevision(), "Wrong automatic reference");
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> decide = () -> {
                start.await();
                try { reviews.decide(unlink, "developer"); return true; }
                catch (IllegalStateException conflict) {
                    assertThat(conflict).hasMessageContaining("match changed"); return false;
                }
            };
            var first = executor.submit(decide);
            var second = executor.submit(decide);
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(payment(receipt.externalId()).documentId()).isNull();
        assertThat(payment(receipt.externalId()).matchMode()).isEqualTo(MatchMode.EXCLUDED);
        assertThat(payment(receipt.externalId()).explanation()).isEqualTo(MatchExplanation.EXCLUDED_BY_USER);
        assertThat(findDocument("10001").paidAmount()).isEqualByComparingTo("0");
        service.sync();
        tx.executeWithoutResult(status -> { store.lock(); store.reconcile(); });
        assertThat(payment(receipt.externalId()).documentId()).isNull();
        var excluded = payment(receipt.externalId());
        reviews.decide(new PaymentReviewInput("DEMO-FI-EUR", receipt.externalId(), ReviewAction.RESUME,
                null, excluded.reviewRevision(), "Confirmed original reference"), "developer");
        assertThat(payment(receipt.externalId()).documentId()).isEqualTo(receipt.documentId());
        assertThat(findDocument("10001").paidAmount()).isEqualByComparingTo("3200");
        var history = reviews.history("DEMO-FI-EUR", receipt.externalId());
        assertThat(history).hasSize(2);
        assertThat(history.getFirst().action()).isEqualTo(ReviewAction.RESUME);
        assertThat(history.getLast().previousDocumentLabel()).isEqualTo("Aurora Design Oy / 10001");
        assertThat(history.getLast().nextMode()).isEqualTo(MatchMode.EXCLUDED);
    }

    @Test
    void reviewReasonsAndValidationRejectUnsafeLinksWithoutChangingFacts() throws Exception {
        service.seedDemo();
        service.sync();
        service.create(new DocumentInput(Kind.EXPENSE, "Small", "SMALL", new BigDecimal("20"),
                LocalDate.of(2026, 11, 1)));
        service.create(new DocumentInput(Kind.EXPENSE, "Cancelled", "CANCELLED", new BigDecimal("100"),
                LocalDate.of(2026, 11, 1)));
        service.cancel(findDocument("CANCELLED").id(), new RevisionInput(0L));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(service.dashboard(30, 0).status().pendingEvents()).isZero());
        tx.executeWithoutResult(status -> {
            store.lock();
            store.jdbc().update("""
                    INSERT INTO bank_transaction VALUES
                    ('DEMO-FI-EUR','unknown','2026-10-29',10,'UNKNOWN','Customer'),
                    ('DEMO-FI-EUR','wrong-direction','2026-10-29',10,'20002','Customer'),
                    ('DEMO-FI-EUR','excess','2026-10-29',-21,'SMALL','Supplier'),
                    ('DEMO-FI-EUR','cancelled','2026-10-29',-10,'CANCELLED','Supplier'),
                    ('DEMO-FI-EUR','zero','2026-10-29',0,'SMALL','Supplier')
                    """);
            store.reconcile();
        });
        assertThat(payment("unknown").explanation()).isEqualTo(MatchExplanation.UNKNOWN_REFERENCE);
        assertThat(payment("wrong-direction").explanation()).isEqualTo(MatchExplanation.DIRECTION_MISMATCH);
        assertThat(payment("excess").explanation()).isEqualTo(MatchExplanation.EXCESS_AMOUNT);
        assertThat(payment("cancelled").explanation()).isEqualTo(MatchExplanation.CANCELLED_DOCUMENT);
        assertThat(payment("zero").explanation()).isEqualTo(MatchExplanation.ZERO_AMOUNT);
        var software = payment("demo-unmatched-1");
        for (String reference : List.of("SMALL", "CANCELLED", "10002")) {
            assertThatThrownBy(() -> reviews.decide(new PaymentReviewInput("DEMO-FI-EUR", software.externalId(),
                    ReviewAction.LINK, findDocument(reference).id(), software.reviewRevision(), "Review"), "developer"))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(reviews.history("DEMO-FI-EUR", software.externalId())).isEmpty();
        assertThat(payment(software.externalId()).reviewRevision()).isEqualTo(software.reviewRevision());
        String body = """
                {"accountId":"DEMO-FI-EUR","transactionId":"demo-unmatched-1","action":"LINK",
                 "revision":0,"reason":" "}
                """;
        mvc.perform(post("/api/reconciliation/decisions").contentType("application/json").content(body)
                .with(httpBasic("developer", "integration-test-password"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/reconciliation/decisions").contentType("application/json").content(body)
                .with(csrf()).with(httpBasic("developer", "integration-test-password"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reconciliation/history").param("accountId", "DEMO-FI-EUR").param("transactionId", "unknown"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reconciliation/history").param("accountId", "OTHER").param("transactionId", "unknown")
                .with(httpBasic("developer", "integration-test-password"))).andExpect(status().isNotFound());
        assertThat(store.account().bookedBalance()).isEqualByComparingTo("12500");
    }

    @Test
    void competingLinksCannotOverAllocateTheSameDocument() throws Exception {
        service.sync();
        service.create(new DocumentInput(Kind.EXPENSE, "Supplier", "TARGET", new BigDecimal("100"),
                LocalDate.of(2026, 11, 1)));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(service.dashboard(30, 0).status().pendingEvents()).isZero());
        tx.executeWithoutResult(status -> {
            store.lock();
            store.jdbc().update("""
                    INSERT INTO bank_transaction VALUES
                    ('DEMO-FI-EUR','competing-a','2026-10-29',-60,'','Supplier'),
                    ('DEMO-FI-EUR','competing-b','2026-10-29',-60,'','Supplier')
                    """);
        });
        var target = findDocument("TARGET");
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.function.Function<String, java.util.concurrent.Callable<Boolean>> decide = id -> () -> {
                start.await();
                try {
                    reviews.decide(new PaymentReviewInput("DEMO-FI-EUR", id, ReviewAction.LINK,
                            target.id(), 0L, "Verified receipt"), "developer");
                    return true;
                } catch (IllegalStateException conflict) {
                    assertThat(conflict).hasMessageContaining("exceeds"); return false;
                }
            };
            var first = executor.submit(decide.apply("competing-a"));
            var second = executor.submit(decide.apply("competing-b"));
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(findDocument("TARGET").paidAmount()).isEqualByComparingTo("60");
        assertThat(reviews.history("DEMO-FI-EUR", "competing-a").size()
                + reviews.history("DEMO-FI-EUR", "competing-b").size()).isEqualTo(1);
    }

    @Test
    void monthlyExpensesAreIdempotentCatchUpAndRetainOriginalMonthAfterIndividualEdits() throws Exception {
        UUID id = UUID.randomUUID();
        var input = new RecurringExpenseInput(id, "Monthly rent", new BigDecimal("100"),
                LocalDate.of(2026, 10, 31), null);
        recurring.create(input);
        recurring.create(input);
        recurring.generate();
        var original = store.documents();
        assertThat(original).hasSize(3);
        assertThat(original).extracting(Document::dueDate).containsExactly(
                LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 31));
        assertThat(original).allMatch(document -> id.equals(document.recurringExpenseId()));
        assertThat(original).extracting(Document::reference).doesNotHaveDuplicates();
        var october = original.getFirst();
        service.update(october.id(), new DocumentUpdate(new DocumentInput(Kind.EXPENSE, "Adjusted rent",
                october.reference(), new BigDecimal("110"), october.dueDate(), october.dueDate().plusDays(5)), 0L));
        service.cancel(original.get(1).id(), new RevisionInput(0L));
        tx.executeWithoutResult(status -> {
            store.lock();
            store.jdbc().update("UPDATE recurring_expense SET next_month=? WHERE id=?", input.firstDueDate().withDayOfMonth(1), id);
        });
        recurring.generate();
        assertThat(store.documents()).hasSize(3);
        assertThat(store.documents().getFirst().expectedDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(store.documents().get(1).cancelled()).isTrue();
        assertThatThrownBy(() -> service.update(october.id(), new DocumentUpdate(new DocumentInput(Kind.INVOICE,
                "Rent", october.reference(), october.amount(), october.dueDate()), 1L)))
                .hasMessageContaining("remain an expense");
        var future = new RecurringExpenseService(store, tx,
                Clock.fixed(Instant.parse("2027-02-01T10:00:00Z"), ZoneId.of("Europe/Helsinki")));
        future.generate();
        assertThat(store.documents()).hasSize(7);
        assertThat(store.documents()).extracting(Document::occurrenceMonth).doesNotHaveDuplicates();
        assertThat(store.documents()).extracting(Document::dueDate).contains(
                LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28),
                LocalDate.of(2027, 3, 31), LocalDate.of(2027, 4, 30));
        assertThat(store.documents().get(1).cancelled()).isTrue();
        future.generate();
        assertThat(store.documents()).hasSize(7);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(service.dashboard(90, 0).status().pendingEvents()).isZero());
    }

    @Test
    void inclusiveEndAndStopPreserveOccurrencesAndPaidGuards() throws Exception {
        service.sync();
        UUID id = UUID.randomUUID();
        recurring.create(new RecurringExpenseInput(id, "Rent", new BigDecimal("100"),
                LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 29)));
        assertThat(store.documents()).hasSize(2);
        var first = store.documents().getFirst();
        tx.executeWithoutResult(status -> {
            store.lock();
            store.jdbc().update("INSERT INTO bank_transaction VALUES ('DEMO-FI-EUR','recurring-paid','2026-10-29',-40,?,'Rent')",
                    first.reference());
            store.reconcile();
        });
        assertThat(store.documents().getFirst().paidAmount()).isEqualByComparingTo("40");
        assertThatThrownBy(() -> service.cancel(first.id(), new RevisionInput(0L))).hasMessageContaining("matched payments");
        assertThatThrownBy(() -> service.update(first.id(), new DocumentUpdate(new DocumentInput(Kind.EXPENSE,
                "Rent", first.reference(), new BigDecimal("39"), first.dueDate()), 0L))).hasMessageContaining("lower than");
        var before = store.documents();
        recurring.stop(id, new RevisionInput(0L));
        assertThat(store.recurringExpenses().getFirst().stopped()).isTrue();
        assertThat(store.recurringExpenses().getFirst().revision()).isEqualTo(1);
        assertThatThrownBy(() -> recurring.stop(id, new RevisionInput(0L))).hasMessageContaining("already stopped");
        new RecurringExpenseService(store, tx, Clock.fixed(Instant.parse("2027-03-01T10:00:00Z"), ZoneId.of("Europe/Helsinki"))).generate();
        assertThat(store.documents()).containsExactlyElementsOf(before);
        UUID ended = UUID.randomUUID();
        recurring.create(new RecurringExpenseInput(ended, "One month", new BigDecimal("10"),
                LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 29)));
        assertThat(store.documents().stream().filter(doc -> ended.equals(doc.recurringExpenseId()))).hasSize(1);
    }

    @Test
    void recurringApiValidatesDatesAndRequestsAndSerializesConcurrentGeneration() throws Exception {
        UUID id = UUID.randomUUID();
        String body = """
                {"requestId":"%s","counterparty":"Salary","amount":100,"firstDueDate":"2026-10-31"}
                """.formatted(id);
        mvc.perform(post("/api/recurring-expenses").with(httpBasic("developer", "integration-test-password"))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/recurring-expenses").with(httpBasic("developer", "integration-test-password")).with(csrf())
                .contentType("application/json").content(body.replace(":100", ":-1"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/recurring-expenses").with(httpBasic("developer", "integration-test-password")).with(csrf())
                .contentType("application/json").content(body.replace("2026-10-31", "2026-10-28"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/recurring-expenses").with(httpBasic("developer", "integration-test-password")).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isNoContent());
        mvc.perform(post("/api/recurring-expenses").with(httpBasic("developer", "integration-test-password")).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isNoContent());
        mvc.perform(post("/api/recurring-expenses").with(httpBasic("developer", "integration-test-password")).with(csrf())
                .contentType("application/json").content(body.replace(":100", ":101"))).andExpect(status().isConflict());
        assertThatThrownBy(() -> recurring.create(new RecurringExpenseInput(UUID.randomUUID(), "Invalid",
                BigDecimal.ONE, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 31)))).hasMessageContaining("End date");
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(recurring::generate);
            var second = executor.submit(recurring::generate);
            first.get(); second.get();
        }
        assertThat(store.recurringExpenses()).hasSize(1);
        assertThat(store.documents()).hasSize(3);
        mvc.perform(post("/api/recurring-expenses/" + id + "/stop").with(httpBasic("developer", "integration-test-password")).with(csrf())
                .contentType("application/json").content("{\"revision\":9}")).andExpect(status().isConflict());
        mvc.perform(post("/api/recurring-expenses/" + UUID.randomUUID() + "/stop").with(httpBasic("developer", "integration-test-password")).with(csrf())
                .contentType("application/json").content("{\"revision\":0}")).andExpect(status().isNotFound());
    }

    @Test
    void generationFailureRollsBackAllOccurrencesAndSurfacesAnExplicitError() {
        UUID id = UUID.randomUUID();
        recurring.create(new RecurringExpenseInput(id, "Future rent", new BigDecimal("100"),
                LocalDate.of(2027, 2, 1), null));
        assertThat(store.documents()).isEmpty();
        String conflicting = "REC-" + id + "-2027-03";
        service.create(new DocumentInput(Kind.EXPENSE, "Existing plan", conflicting, BigDecimal.TEN,
                LocalDate.of(2027, 3, 1)));
        var future = new RecurringExpenseService(store, tx,
                Clock.fixed(Instant.parse("2027-02-01T10:00:00Z"), ZoneId.of("Europe/Helsinki")));
        future.scheduledGeneration();
        assertThat(store.documents()).hasSize(1);
        assertThat(store.recurringExpenses().getFirst().nextMonth()).isEqualTo(LocalDate.of(2027, 2, 1));
        assertThat(store.status().recurrenceError()).contains("generation failed");
        var document = store.documents().getFirst();
        service.update(document.id(), new DocumentUpdate(new DocumentInput(Kind.EXPENSE, "Existing plan",
                "UNBLOCKED", BigDecimal.TEN, document.dueDate()), document.revision()));
        future.generate();
        assertThat(store.documents()).hasSize(5);
        assertThat(store.status().recurrenceError()).isNull();
        assertThat(store.recurringExpenses().getFirst().nextDueDate()).isEqualTo(LocalDate.of(2027, 6, 1));
    }

    BankTransaction payment(String externalId) {
        return store.transactions().stream().filter(t -> t.externalId().equals(externalId)).findFirst().orElseThrow();
    }

    Document findDocument(String reference) {
        return store.documents().stream().filter(document -> document.reference().equals(reference)).findFirst().orElseThrow();
    }
}
