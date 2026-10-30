package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties = {"app.password=integration-test-password", "spring.datasource.password=test",
        "app.outbox-delay-ms=3600000", "app.recurrence-delay-ms=3600000",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"})
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 3, topics = "cashflow.finance-changed.v1")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext
class EventPipelineIntegrationTest {
    @Autowired FinanceService service;
    @Autowired FinanceStore store;
    @Autowired EventPipeline events;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper mapper;
    @Autowired MeterRegistry metrics;
    @Autowired DefaultErrorHandler handler;
    @Autowired MockMvc mvc;
    static final String TOPIC = "cashflow.finance-changed.v1";

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
            store.jdbc().update("DELETE FROM consumer_failure");
            store.jdbc().update("UPDATE workspace SET planning_balance=NULL,last_sync=NULL,sync_error=NULL,last_reconciled=NULL,recurrence_error=NULL");
        });
    }

    ConsumerRecord<String, String> record(UUID id, long offset) throws Exception {
        return new ConsumerRecord<>(TOPIC, 0, offset, "workspace-1",
                mapper.writeValueAsString(new FinanceEvent(id, "BANK_SYNCED", 1)));
    }

    @Test
    void publicationFailuresRetainEventsAndReplayDoesNotRepeatReconciliation() throws Exception {
        tx.executeWithoutResult(status -> { store.lock(); store.enqueue("BANK_SYNCED"); });
        UUID id = store.jdbc().queryForObject("SELECT id FROM outbox", UUID.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> unavailable = mock(KafkaTemplate.class);
        when(unavailable.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("private upstream details")))
                .thenThrow(new org.springframework.kafka.KafkaException("private synchronous send error"));
        double failures = metrics.counter("cashflow.events.publication.failures").count();
        new EventPipeline(store, tx, unavailable, mapper, TOPIC, metrics).publish();
        var failed = service.dashboard(30, 0).status();
        assertThat(failed.pendingEvents()).isEqualTo(1);
        assertThat(failed.pipeline().unpublishedEvents()).isEqualTo(1);
        assertThat(failed.pipeline().awaitingReconciliation()).isZero();
        assertThat(failed.pipeline().oldestPendingAt()).isNotNull();
        assertThat(failed.eventError()).contains("retained").doesNotContain("private");
        assertThat(metrics.counter("cashflow.events.publication.failures").count()).isEqualTo(failures + 1);
        assertThat(store.jdbc().queryForObject("SELECT published_at IS NULL FROM outbox WHERE id=?", Boolean.class, id)).isTrue();
        new EventPipeline(store, tx, unavailable, mapper, TOPIC, metrics).publish();
        assertThat(store.status().eventError()).contains("retained").doesNotContain("private");
        assertThat(metrics.counter("cashflow.events.publication.failures").count()).isEqualTo(failures + 2);

        events.publish();
        var published = service.dashboard(30, 0).status();
        assertThat(published.eventError()).isNull();
        assertThat(published.pipeline().unpublishedEvents()).isZero();
        assertThat(published.pipeline().awaitingReconciliation()).isEqualTo(1);
        events.consume(record(id, 1));
        Instant reconciled = store.status().lastReconciled();
        Instant processed = store.jdbc().queryForObject("SELECT processed_at FROM processed_event WHERE id=?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), id);
        double duplicates = metrics.counter("cashflow.events.duplicates").count();
        // Simulate acknowledgement/marking uncertainty: the same outbox ID is published again.
        store.jdbc().update("UPDATE outbox SET published_at=NULL WHERE id=?", id);
        events.publish();
        events.consume(record(id, 2));
        assertThat(store.status().lastReconciled()).isEqualTo(reconciled);
        Instant replayed = store.jdbc().queryForObject("SELECT processed_at FROM processed_event WHERE id=?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), id);
        assertThat(replayed).isEqualTo(processed);
        assertThat(metrics.counter("cashflow.events.duplicates").count()).isEqualTo(duplicates + 1);
        assertThat(store.status().pendingEvents()).isZero();
        assertThat(store.status().pipeline().oldestPendingAt()).isNull();
    }

    @Test
    void reconciliationFailureRollsBackMarkerAndAllocationsAndSuccessfulRetryClearsFailure() throws Exception {
        service.seedDemo();
        service.sync();
        UUID id = UUID.randomUUID();
        var record = record(id, 10);
        double processed = metrics.counter("cashflow.events.processed").count();
        store.jdbc().execute("""
                CREATE FUNCTION reject_reconciliation() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected reconciliation failure'; END $$
                """);
        store.jdbc().execute("""
                CREATE TRIGGER reject_reconciliation BEFORE UPDATE OF last_reconciled ON workspace
                FOR EACH ROW EXECUTE FUNCTION reject_reconciliation()
                """);
        try {
            assertThatThrownBy(() -> events.consume(record)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(store.jdbc().queryForObject("SELECT count(*) FROM processed_event WHERE id=?", Integer.class, id)).isZero();
            assertThat(store.jdbc().queryForObject("SELECT count(*) FROM allocation", Integer.class)).isZero();
            assertThat(store.status().lastReconciled()).isNull();
            assertThat(metrics.counter("cashflow.events.processed").count()).isEqualTo(processed);
            events.recordConsumerFailure(record, new IllegalStateException("sensitive exception text"), 1);
            assertThat(store.status().pipeline().failedPartitions()).isEqualTo(1);
            assertThat(store.status().pipeline().consumerError()).contains("retained").doesNotContain("sensitive");
        } finally {
            store.jdbc().execute("DROP TRIGGER reject_reconciliation ON workspace");
            store.jdbc().execute("DROP FUNCTION reject_reconciliation()");
        }
        events.consume(record(UUID.randomUUID(), 9));
        assertThat(store.status().pipeline().failedPartitions()).isEqualTo(1);
        events.consume(record);
        assertThat(store.status().pipeline().failedPartitions()).isZero();
        assertThat(store.status().pipeline().consumerError()).isNull();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM processed_event WHERE id=?", Integer.class, id)).isEqualTo(1);
        assertThat(service.dashboard(30, 0).forecast().minimumBalance()).isEqualByComparingTo("-2100");
    }

    @Test
    void evenDefaultFatalExceptionsAreRetriedAndDiagnosticsAreAuthenticated() throws Exception {
        var record = record(UUID.randomUUID(), 20);
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        assertThat(handler.handleOne(new ClassCastException("must not skip"), record, consumer, container)).isFalse();
        assertThat(handler.handleOne(new ClassCastException("must still not skip"), record, consumer, container)).isFalse();
        assertThat(store.jdbc().queryForObject("SELECT attempts FROM consumer_failure WHERE topic=? AND partition_id=0",
                Long.class, TOPIC)).isEqualTo(2);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM processed_event", Integer.class)).isZero();
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/metrics/cashflow.events.consumer.failures")
                        .with(httpBasic("developer", "integration-test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.measurements[0].value").isNumber());
        mvc.perform(get("/api/dashboard").with(httpBasic("developer", "integration-test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status.pipeline.failedPartitions").value(1));
        for (String invalid : List.of("null", "{}", "{\"id\":null}", "{\"id\":\"" + UUID.randomUUID()
                + "\",\"type\":\"UNKNOWN\",\"schemaVersion\":1}")) {
            assertThatThrownBy(() -> events.consume(new ConsumerRecord<>(TOPIC, 0, 20, "workspace-1", invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        events.consume(record);
        assertThat(store.status().pipeline().failedPartitions()).isZero();
    }
}
