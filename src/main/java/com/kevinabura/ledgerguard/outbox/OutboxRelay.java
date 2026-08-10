package com.kevinabura.ledgerguard.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ledgerguard.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final int batchSize;
    private final long publishTimeoutMillis;
    private final Counter published;
    private final Counter failed;
    private final Counter leaseLost;

    public OutboxRelay(
            OutboxRepository repository,
            KafkaTemplate<String, String> kafka,
            @Value("${ledgerguard.outbox.topic:ledger.events.v1}") String topic,
            @Value("${ledgerguard.outbox.batch-size:50}") int batchSize,
            @Value("${ledgerguard.outbox.publish-timeout-ms:10000}") long publishTimeoutMillis,
            MeterRegistry meterRegistry) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Outbox topic must not be blank");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Outbox batch size must be positive");
        }
        if (publishTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Outbox publish timeout must be positive");
        }
        this.repository = repository;
        this.kafka = kafka;
        this.topic = topic;
        this.batchSize = batchSize;
        this.publishTimeoutMillis = publishTimeoutMillis;
        this.published = meterRegistry.counter("ledgerguard.outbox.events", "result", "published");
        this.failed = meterRegistry.counter("ledgerguard.outbox.events", "result", "failed");
        this.leaseLost = meterRegistry.counter("ledgerguard.outbox.events", "result", "lease_lost");
    }

    @Scheduled(fixedDelayString = "${ledgerguard.outbox.poll-delay-ms:500}")
    public void publishAvailable() {
        var events = repository.claimBatch(batchSize);
        if (events.isEmpty()) {
            return;
        }

        // Kafka send can block while waiting for metadata. Dispatch the batch concurrently
        // so one timeout window covers it instead of batchSize windows that outlive the lease.
        var dispatcher = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var pending = events.stream()
                    .map(event -> new PendingPublish(
                            event,
                            CompletableFuture.supplyAsync(
                                            () -> kafka.send(
                                                    topic, event.aggregateId().toString(), event.payload()),
                                            dispatcher)
                                    .thenCompose(result -> result)
                                    .orTimeout(publishTimeoutMillis, TimeUnit.MILLISECONDS)))
                    .toList();

            for (var publish : pending) {
                try {
                    publish.result().join();
                    if (repository.markPublished(publish.event())) {
                        published.increment();
                    } else {
                        leaseLost.increment();
                    }
                } catch (CompletionException exception) {
                    recordFailure(
                            publish.event(), exception.getCause() == null ? exception : exception.getCause());
                }
            }
        } finally {
            dispatcher.shutdownNow();
        }
    }

    private void recordFailure(OutboxEvent event, Throwable error) {
        if (repository.markFailed(event, error)) {
            failed.increment();
        } else {
            leaseLost.increment();
        }
    }

    private record PendingPublish(
            OutboxEvent event,
            CompletableFuture<SendResult<String, String>> result) {}
}
