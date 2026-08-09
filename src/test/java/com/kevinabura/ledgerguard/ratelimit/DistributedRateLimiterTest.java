package com.kevinabura.ledgerguard.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class DistributedRateLimiterTest {

    @Test
    void redisFailureUsesSmallerLocalBucketAndEmitsMetrics() {
        var metrics = new SimpleMeterRegistry();
        var limiter = new DistributedRateLimiter(
                new FailingRedisTemplate(),
                new LocalTokenBucket(Clock.systemUTC(), 2, 1),
                metrics);

        var first = limiter.consume("client-route", 100, 50);
        var second = limiter.consume("client-route", 100, 50);
        var third = limiter.consume("client-route", 100, 50);

        assertThat(first.allowed()).isTrue();
        assertThat(second.allowed()).isTrue();
        assertThat(third.allowed()).isFalse();
        assertThat(first.fallback()).isTrue();
        assertThat(metrics.counter("ledgerguard.rate_limit.redis_errors").count()).isEqualTo(3);
        assertThat(metrics.counter("ledgerguard.rate_limit.fallback").count()).isEqualTo(3);
    }

    private static final class FailingRedisTemplate extends StringRedisTemplate {
        @Override
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            throw new RedisConnectionFailureException("redis unavailable");
        }
    }
}
