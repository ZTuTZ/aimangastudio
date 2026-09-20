package com.aimanga.v2.integration;

import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.task.RedisConcurrencyLimiter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class InfrastructureIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("aimanga_v2").withUsername("aimanga").withPassword("aimanga");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Test
    void flywayBuildsEmptyDatabaseAndRedisResponds() throws Exception {
        var result = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load().migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThan(0);

        try (Socket socket = new Socket(REDIS.getHost(), REDIS.getMappedPort(6379))) {
            OutputStream out = socket.getOutputStream();
            out.write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String response = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            assertThat(response).isEqualTo("+PONG");
        }
    }

    @Test
    void flywayUpgradesV803WithoutOverwritingOperatorConfiguration() throws Exception {
        String upgradeDb = "aimanga_upgrade";
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + upgradeDb + " CHARACTER SET utf8mb4");
        }
        String upgradeUrl = MYSQL.getJdbcUrl().replace("/aimanga_v2", "/" + upgradeDb);
        Flyway before = Flyway.configure()
                .dataSource(upgradeUrl, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("8.0.3")
                .load();
        before.migrate();
        try (var connection = DriverManager.getConnection(upgradeUrl, MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO system_config(config_key,config_value,config_group) " +
                    "VALUES ('task_max_concurrency','19','task') ON DUPLICATE KEY UPDATE config_value='19'");
        }

        Flyway.configure().dataSource(upgradeUrl, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();

        try (var connection = DriverManager.getConnection(upgradeUrl, MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery(
                    "SELECT config_value FROM system_config WHERE config_key='task_max_concurrency'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("19");
            }
            try (var result = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() " +
                            "AND table_name IN ('task_plan_unit','export_artifact')")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(2);
            }
        }
    }

    @Test
    void redisLimiterAtomicallyCapsTwoClientsAndCannotRenewExpiredToken() throws Exception {
        String address = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        RedissonClient first = client(address);
        RedissonClient second = client(address);
        var executor = Executors.newFixedThreadPool(20);
        try {
            first.getKeys().deleteByPattern("aimanga:v2:limit:integration:*");
            RedisConcurrencyLimiter one = new RedisConcurrencyLimiter(first, mock(ConfigService.class));
            RedisConcurrencyLimiter two = new RedisConcurrencyLimiter(second, mock(ConfigService.class));
            CountDownLatch ready = new CountDownLatch(20);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger acquired = new AtomicInteger();
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                RedisConcurrencyLimiter limiter = i % 2 == 0 ? one : two;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (limiter.tryAcquire("integration", "shared", 5, 30_000) != null) {
                        acquired.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            assertThat(acquired.get()).isEqualTo(5);

            String shortToken = one.tryAcquire("integration", "expiring", 1, 50);
            assertThat(shortToken).isNotNull();
            Thread.sleep(100);
            assertThat(one.renew("integration", "expiring", shortToken, 1_000)).isFalse();
        } finally {
            executor.shutdownNow();
            first.shutdown();
            second.shutdown();
        }
    }

    private static RedissonClient client(String address) {
        Config config = new Config();
        config.useSingleServer().setAddress(address);
        return Redisson.create(config);
    }
}
