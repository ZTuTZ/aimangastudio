package com.aimanga.v2.integration;

import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.task.RedisConcurrencyLimiter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisConcurrencyLimiterIT {

    private static GenericContainer<?> redis;
    private static String redisAddress;

    @BeforeAll
    static void startRedis() {
        String externalAddress = System.getProperty("redis.it.address");
        if (externalAddress != null && !externalAddress.isBlank()) {
            redisAddress = externalAddress;
            return;
        }

        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker or -Dredis.it.address is required for Redis integration tests");
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redis.start();
        redisAddress = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.stop();
        }
    }

    @BeforeEach
    void clearLimiterKeys() {
        try (ClientHandle handle = client()) {
            handle.client().getKeys().deleteByPattern("aimanga:v2:limit:*");
        }
    }

    @Test
    void releaseUsesTheSameTokenEncodingAndIsIdempotent() {
        try (ClientHandle first = client(); ClientHandle second = client()) {
            RedisConcurrencyLimiter one = limiter(first.client());
            RedisConcurrencyLimiter two = limiter(second.client());

            String tokenA = one.tryAcquire("integration", "release", 1, 30_000);
            assertThat(tokenA).isNotNull();

            two.release("integration", "release", "not-the-owner");
            assertThat(two.tryAcquire("integration", "release", 1, 30_000)).isNull();

            one.release("integration", "release", tokenA);
            one.release("integration", "release", tokenA);
            assertThat(two.tryAcquire("integration", "release", 1, 30_000)).isNotNull();
        }
    }

    @Test
    void renewExtendsOnlyAStillLiveToken() throws Exception {
        try (ClientHandle first = client(); ClientHandle second = client()) {
            RedisConcurrencyLimiter one = limiter(first.client());
            RedisConcurrencyLimiter two = limiter(second.client());

            String token = one.tryAcquire("integration", "renew", 1, 400);
            assertThat(token).isNotNull();
            Thread.sleep(250);
            assertThat(one.renew("integration", "renew", token, 800)).isTrue();
            Thread.sleep(300);
            assertThat(two.tryAcquire("integration", "renew", 1, 30_000)).isNull();

            String expired = one.tryAcquire("integration", "expired", 1, 200);
            assertThat(expired).isNotNull();
            Thread.sleep(350);
            assertThat(one.renew("integration", "expired", expired, 30_000)).isFalse();
        }
    }

    @Test
    void loweringCapacityDoesNotGrantUntilUsageFallsBelowTheNewMaximum() {
        try (ClientHandle handle = client()) {
            RedisConcurrencyLimiter limiter = limiter(handle.client());
            List<String> tokens = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                tokens.add(limiter.tryAcquire("integration", "resize", 5, 30_000));
            }
            assertThat(tokens).doesNotContainNull();
            assertThat(limiter.tryAcquire("integration", "resize", 2, 30_000)).isNull();

            for (int i = 0; i < 4; i++) {
                limiter.release("integration", "resize", tokens.get(i));
            }
            assertThat(limiter.tryAcquire("integration", "resize", 2, 30_000)).isNotNull();
        }
    }

    @Test
    void occupancyUsesRedisTimeWithoutChangingStoredPermits() {
        try (ClientHandle handle = client()) {
            RedisConcurrencyLimiter limiter = limiter(handle.client());
            String token = limiter.tryAcquire("ai", "image", 10, 30_000);
            assertThat(token).isNotNull();

            String key = "aimanga:v2:limit:ai:image";
            RScoredSortedSet<String> permits = handle.client()
                    .getScoredSortedSet(key, StringCodec.INSTANCE);
            permits.add(System.currentTimeMillis() - 60_000, "expired-but-not-yet-cleaned");
            assertThat(permits.size()).isEqualTo(2);

            var occupancy = limiter.aiOccupancy();

            assertThat(occupancy.get("image")).containsExactly(1, 10);
            assertThat(permits.size()).isEqualTo(2);
            assertThat(permits.contains("expired-but-not-yet-cleaned")).isTrue();
            assertThat(permits.contains(token)).isTrue();
        }
    }

    @Test
    void concurrentClientsNeverExceedCapacity() throws Exception {
        try (ClientHandle first = client(); ClientHandle second = client()) {
            RedisConcurrencyLimiter one = limiter(first.client());
            RedisConcurrencyLimiter two = limiter(second.client());
            var executor = Executors.newFixedThreadPool(20);
            try {
                CountDownLatch ready = new CountDownLatch(20);
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger acquired = new AtomicInteger();
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    RedisConcurrencyLimiter candidate = i % 2 == 0 ? one : two;
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        if (candidate.tryAcquire("integration", "shared", 5, 30_000) != null) {
                            acquired.incrementAndGet();
                        }
                        return null;
                    }));
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                for (var future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }
                assertThat(acquired.get()).isEqualTo(5);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static RedisConcurrencyLimiter limiter(RedissonClient client) {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getInt(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return new RedisConcurrencyLimiter(client, configService);
    }

    private static ClientHandle client() {
        Config config = new Config();
        config.useSingleServer().setAddress(redisAddress);
        return new ClientHandle(Redisson.create(config));
    }

    private record ClientHandle(RedissonClient client) implements AutoCloseable {
        @Override
        public void close() {
            client.shutdown();
        }
    }
}
