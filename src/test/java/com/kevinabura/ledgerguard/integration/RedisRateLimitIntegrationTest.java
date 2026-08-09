package com.kevinabura.ledgerguard.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.kevinabura.ledgerguard.ratelimit.DistributedRateLimiter;
import com.kevinabura.ledgerguard.ratelimit.LocalTokenBucket;
import com.kevinabura.ledgerguard.ratelimit.RateLimitDecision;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RedisRateLimitIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;

    @BeforeEach
    void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
    }

    @AfterEach
    void disconnect() {
        connectionFactory.destroy();
    }

    @Test
    void twoApplicationInstancesShareOneAtomicCapacity() throws Exception {
        var redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        var registry = new SimpleMeterRegistry();
        var first = new DistributedRateLimiter(
                redis, new LocalTokenBucket(Clock.systemUTC(), 10, 1), registry);
        var second = new DistributedRateLimiter(
                redis, new LocalTokenBucket(Clock.systemUTC(), 10, 1), registry);
        var executor = Executors.newFixedThreadPool(16);
        var futures = new ArrayList<java.util.concurrent.Future<RateLimitDecision>>();

        for (int index = 0; index < 100; index++) {
            var limiter = index % 2 == 0 ? first : second;
            futures.add(executor.submit(() -> limiter.consume("shared-client-route", 40, 1)));
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        var decisions = futures.stream().map(future -> {
            try {
                return future.get();
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }).toList();
        assertThat(decisions).noneMatch(RateLimitDecision::fallback);
        assertThat(decisions.stream().filter(RateLimitDecision::allowed)).hasSize(40);
    }
}
