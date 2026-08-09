package com.kevinabura.ledgerguard.ratelimit;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalTokenBucket {
    private static final int MAX_BUCKETS = 10_000;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int fallbackCapacity;
    private final int fallbackRefill;

    public LocalTokenBucket(
            Clock clock,
            @Value("${ledgerguard.rate-limit.fallback-capacity:20}") int fallbackCapacity,
            @Value("${ledgerguard.rate-limit.fallback-refill-per-second:10}") int fallbackRefill) {
        this.clock = clock;
        this.fallbackCapacity = fallbackCapacity;
        this.fallbackRefill = fallbackRefill;
    }

    public RateLimitDecision consume(String key, int requestedCapacity, int requestedRefill) {
        var capacity = Math.min(requestedCapacity, fallbackCapacity);
        var refill = Math.min(requestedRefill, fallbackRefill);
        var now = clock.millis();
        if (buckets.size() >= MAX_BUCKETS && !buckets.containsKey(key)) {
            prune(now);
            if (buckets.size() >= MAX_BUCKETS) {
                // Memory pressure must not reset every bucket and create a new admission burst.
                return new RateLimitDecision(false, 0, 1000, true);
            }
        }
        var bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(capacity, now));
        synchronized (bucket) {
            var elapsedMillis = Math.max(0, now - bucket.updatedAtMillis);
            bucket.tokens = Math.min(capacity, bucket.tokens + elapsedMillis * (refill / 1000.0));
            bucket.updatedAtMillis = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return new RateLimitDecision(true, (long) Math.floor(bucket.tokens), 0, true);
            }
            var retry = Math.max(1L, (long) Math.ceil((1.0 - bucket.tokens) * 1000.0 / refill));
            return new RateLimitDecision(false, 0, retry, true);
        }
    }

    private void prune(long now) {
        var staleBefore = now - 10 * 60_000L;
        buckets.entrySet().removeIf(entry -> entry.getValue().updatedAtMillis < staleBefore);
    }

    static final class Bucket {
        private double tokens;
        private long updatedAtMillis;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.updatedAtMillis = now;
        }
    }
}
