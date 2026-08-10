package com.kevinabura.ledgerguard.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxRelayTest {

    @Test
    @SuppressWarnings("unchecked")
    void dispatchesTheWholeBatchBeforeWaitingForAcknowledgements() throws Exception {
        var repository = mock(OutboxRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var metrics = new SimpleMeterRegistry();
        var first = event();
        var second = event();
        var firstResult = new CompletableFuture<SendResult<String, String>>();
        var secondResult = new CompletableFuture<SendResult<String, String>>();
        when(repository.claimBatch(50)).thenReturn(List.of(first, second));
        when(kafka.send(eq("events"), anyString(), anyString())).thenReturn(firstResult, secondResult);
        when(repository.markPublished(any())).thenReturn(true);
        var relay = new OutboxRelay(repository, kafka, "events", 50, 5_000, metrics);
        var executor = Executors.newSingleThreadExecutor();

        try {
            var run = executor.submit(relay::publishAvailable);
            verify(kafka, timeout(1_000).times(2)).send(eq("events"), anyString(), anyString());
            firstResult.complete(null);
            secondResult.complete(null);
            run.get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        verify(repository).markPublished(first);
        verify(repository).markPublished(second);
        assertThat(metrics.counter("ledgerguard.outbox.events", "result", "published").count())
                .isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsALostLeaseInsteadOfClaimingTheEventWasPublished() {
        var repository = mock(OutboxRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var metrics = new SimpleMeterRegistry();
        var event = event();
        when(repository.claimBatch(1)).thenReturn(List.of(event));
        when(kafka.send("events", event.aggregateId().toString(), event.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markPublished(event)).thenReturn(false);

        new OutboxRelay(repository, kafka, "events", 1, 5_000, metrics).publishAvailable();

        assertThat(metrics.counter("ledgerguard.outbox.events", "result", "published").count())
                .isZero();
        assertThat(metrics.counter("ledgerguard.outbox.events", "result", "lease_lost").count())
                .isEqualTo(1);
    }

    private static OutboxEvent event() {
        return new OutboxEvent(
                UUID.randomUUID(), UUID.randomUUID(), "ledger.transaction.posted.v1", "{}", 0, UUID.randomUUID());
    }
}
