package com.kevinabura.ledgerguard.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LocalTokenBucketTest {

    @Test
    void admitsOnlyCapacityUnderConcurrentLoad() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-08-09T00:00:00Z"));
        var limiter = new LocalTokenBucket(clock, 50, 10);
        var executor = Executors.newFixedThreadPool(16);
        var futures = new ArrayList<java.util.concurrent.Future<RateLimitDecision>>();
        for (int index = 0; index < 100; index++) {
            futures.add(executor.submit(() -> limiter.consume("same-bucket", 50, 10)));
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        var allowed = futures.stream().map(future -> {
            try {
                return future.get();
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }).filter(RateLimitDecision::allowed).count();
        assertThat(allowed).isEqualTo(50);
    }

    @Test
    void refillsUsingElapsedTimeAndProvidesRetryDelay() {
        var clock = new MutableClock(Instant.parse("2026-08-09T00:00:00Z"));
        var limiter = new LocalTokenBucket(clock, 2, 1);

        assertThat(limiter.consume("client", 2, 1).allowed()).isTrue();
        assertThat(limiter.consume("client", 2, 1).allowed()).isTrue();
        assertThat(limiter.consume("client", 2, 1).retryAfterMillis()).isEqualTo(1000);

        clock.advanceMillis(1000);
        assertThat(limiter.consume("client", 2, 1).allowed()).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
