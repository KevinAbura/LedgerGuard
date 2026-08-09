package com.kevinabura.ledgerguard.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ledgerguard.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final int batchSize;
    private final Counter published;
    private final Counter failed;

    public OutboxRelay(
            OutboxRepository repository,
            KafkaTemplate<String, String> kafka,
            @Value("${ledgerguard.outbox.topic:ledger.events.v1}") String topic,
            @Value("${ledgerguard.outbox.batch-size:50}") int batchSize,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafka = kafka;
        this.topic = topic;
        this.batchSize = batchSize;
        this.published = meterRegistry.counter("ledgerguard.outbox.events", "result", "published");
        this.failed = meterRegistry.counter("ledgerguard.outbox.events", "result", "failed");
    }

    @Scheduled(fixedDelayString = "${ledgerguard.outbox.poll-delay-ms:500}")
    public void publishAvailable() {
        for (var event : repository.claimBatch(batchSize)) {
            try {
                kafka.send(topic, event.aggregateId().toString(), event.payload())
                        .get(10, TimeUnit.SECONDS);
                repository.markPublished(event);
                published.increment();
            } catch (Exception exception) {
                repository.markFailed(event, exception);
                failed.increment();
            }
        }
    }
}
