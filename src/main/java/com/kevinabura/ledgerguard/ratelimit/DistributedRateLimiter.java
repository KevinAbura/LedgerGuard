package com.kevinabura.ledgerguard.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class DistributedRateLimiter {
    private static final DefaultRedisScript<List> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local refill_per_second = tonumber(ARGV[2])
            local redis_time = redis.call('TIME')
            local now_ms = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'updated_at_ms')
            local tokens = tonumber(state[1]) or capacity
            local updated_at_ms = tonumber(state[2]) or now_ms
            local elapsed_ms = math.max(0, now_ms - updated_at_ms)
            tokens = math.min(capacity, tokens + elapsed_ms * refill_per_second / 1000)

            local allowed = 0
            local retry_after_ms = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              retry_after_ms = math.ceil((1 - tokens) * 1000 / refill_per_second)
            end

            redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated_at_ms', now_ms)
            redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill_per_second * 2000) + 1000)
            return {allowed, math.floor(tokens), retry_after_ms}
            """, List.class);

    private final StringRedisTemplate redis;
    private final LocalTokenBucket fallback;
    private final Counter allowed;
    private final Counter rejected;
    private final Counter fallbackUsed;
    private final Counter redisErrors;

    public DistributedRateLimiter(
            StringRedisTemplate redis,
            LocalTokenBucket fallback,
            MeterRegistry meterRegistry) {
        this.redis = redis;
        this.fallback = fallback;
        this.allowed = meterRegistry.counter("ledgerguard.rate_limit.decisions", "result", "allowed");
        this.rejected = meterRegistry.counter("ledgerguard.rate_limit.decisions", "result", "rejected");
        this.fallbackUsed = meterRegistry.counter("ledgerguard.rate_limit.fallback");
        this.redisErrors = meterRegistry.counter("ledgerguard.rate_limit.redis_errors");
    }

    public RateLimitDecision consume(String key, int capacity, int refillPerSecond) {
        RateLimitDecision decision;
        try {
            var result = redis.execute(
                    TOKEN_BUCKET_SCRIPT,
                    List.of("ledgerguard:rate:" + key),
                    Integer.toString(capacity),
                    Integer.toString(refillPerSecond));
            if (result == null || result.size() != 3) {
                throw new IllegalStateException("Unexpected Redis rate-limit result");
            }
            decision = new RateLimitDecision(
                    asLong(result.get(0)) == 1,
                    asLong(result.get(1)),
                    asLong(result.get(2)),
                    false);
        } catch (RuntimeException exception) {
            redisErrors.increment();
            fallbackUsed.increment();
            decision = fallback.consume(key, capacity, refillPerSecond);
        }
        if (decision.allowed()) {
            allowed.increment();
        } else {
            rejected.increment();
        }
        return decision;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }
}
