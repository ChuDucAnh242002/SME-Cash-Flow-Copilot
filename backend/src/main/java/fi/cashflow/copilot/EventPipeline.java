package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

@Component
public class EventPipeline {
    private static final Logger log = LoggerFactory.getLogger(EventPipeline.class);
    private final FinanceStore store;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final String topic;

    public EventPipeline(FinanceStore store, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
                         ObjectMapper mapper, @Value("${app.kafka-topic}") String topic) {
        this.store = store; this.tx = tx; this.kafka = kafka; this.mapper = mapper; this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${app.outbox-delay-ms:2000}")
    public void publish() {
        tx.executeWithoutResult(status -> {
            var events = store.jdbc().query("""
                    SELECT id,type FROM outbox WHERE published_at IS NULL
                    ORDER BY created_at,id LIMIT 20 FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> new FinanceEvent(rs.getObject("id", UUID.class), rs.getString("type"), 1));
            for (FinanceEvent event : events) {
                try {
                    // The single-workspace key preserves ordering; only IDs and event types leave the database.
                    kafka.send(topic, "workspace-1", mapper.writeValueAsString(event)).get(20, TimeUnit.SECONDS);
                    store.jdbc().update("UPDATE outbox SET published_at=now(),last_error=NULL WHERE id=?", event.id());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    recordFailure(event.id());
                    return;
                } catch (ExecutionException | TimeoutException | org.apache.kafka.common.KafkaException exception) {
                    log.warn("Kafka publication failed for event {}; retained for retry ({})", event.id(), exception.getClass().getSimpleName());
                    recordFailure(event.id());
                    return;
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException("Cannot serialize finance event", exception);
                }
            }
        });
    }

    private void recordFailure(UUID id) {
        store.jdbc().update("UPDATE outbox SET last_error=? WHERE id=?",
                "Kafka publication is unavailable. Event retained; reconciliation will retry.", id);
    }

    @KafkaListener(topics = "${app.kafka-topic}")
    public void consume(ConsumerRecord<String, String> record) throws JsonProcessingException {
        FinanceEvent event = mapper.readValue(record.value(), FinanceEvent.class);
        if (event.id() == null || event.schemaVersion() != 1
                || !Set.of("BANK_SYNCED", "DOCUMENT_CREATED", "DOCUMENT_UPDATED", "DOCUMENT_CANCELLED",
                        "DEMO_LOADED", "STATEMENT_IMPORTED", "PAYMENT_REVIEWED").contains(event.type())) {
            throw new IllegalArgumentException("Unsupported finance event schema");
        }
        tx.executeWithoutResult(status -> {
            store.lock();
            int inserted = store.jdbc().update("INSERT INTO processed_event(id) VALUES (?) ON CONFLICT DO NOTHING", event.id());
            if (inserted > 0) store.reconcile();
        });
    }

    @Configuration
    static class KafkaConfiguration {
        @Bean
        NewTopic financeTopic(@Value("${app.kafka-topic}") String topic,
                              @Value("${KAFKA_REPLICATION_FACTOR:1}") int replicas) {
            return TopicBuilder.name(topic).partitions(3).replicas(replicas).build();
        }

        @Bean
        DefaultErrorHandler kafkaErrorHandler() {
            return new DefaultErrorHandler(new FixedBackOff(3000, FixedBackOff.UNLIMITED_ATTEMPTS));
        }
    }
}
