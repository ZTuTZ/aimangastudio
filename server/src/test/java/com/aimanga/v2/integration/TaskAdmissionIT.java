package com.aimanga.v2.integration;

import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.service.TaskAdmissionService;
import com.aimanga.v2.service.TaskPlanningService;
import com.aimanga.v2.task.TaskEventPublisher;
import com.aimanga.v2.task.TaskQueue;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = TaskAdmissionIT.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TaskAdmissionIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("aimanga_admission").withUsername("aimanga").withPassword("aimanga");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired TaskAdmissionService admissionService;
    @Autowired TaskPlanningService planningService;
    @Autowired TaskQueue taskQueue;
    @Autowired TaskEventPublisher publisher;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        reset(planningService, taskQueue, publisher);
        jdbc.update("DELETE FROM pipeline_stage_item");
        jdbc.update("DELETE FROM task_plan_unit");
        jdbc.update("DELETE FROM task");
        jdbc.update("DELETE FROM project");
        jdbc.update("INSERT INTO project(id,content_uid,user_id,title,pause_requested,control_version) " +
                "VALUES (7,'00000000-0000-0000-0000-000000000007',1,'准入测试',0,0)");
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void planningFailureRollsBackTaskPlanAndStageItem() {
        doAnswer(invocation -> {
            TaskEntity task = invocation.getArgument(0);
            jdbc.update("INSERT INTO task_plan_unit(task_id,plan_version,unit_no,stage_type,business_type,business_id,input_snapshot,status) " +
                    "VALUES (?,?,?,?,?,?,?,?)", task.getId(), 1, 1, "SCRIPT", "PROJECT", 7, "{}", 0);
            jdbc.update("INSERT INTO pipeline_stage_item(project_id,stage_type,business_type,business_id,status) " +
                    "VALUES (7,'SCRIPT','PROJECT',7,0)");
            throw new IllegalStateException("injected planning failure");
        }).when(planningService).initializePlan(any());

        assertThatThrownBy(() -> admissionService.admit(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected planning failure");

        assertThat(count("task")).isZero();
        assertThat(count("task_plan_unit")).isZero();
        assertThat(count("pipeline_stage_item")).isZero();
        verify(taskQueue, never()).enqueue(anyLong());
        verify(publisher, never()).publishCreated(any());
    }

    @Test
    void uncommittedTaskIsInvisibleAndQueueRunsOnlyAfterCommit() throws Exception {
        CountDownLatch planEntered = new CountDownLatch(1);
        CountDownLatch releasePlan = new CountDownLatch(1);
        doAnswer(invocation -> {
            planEntered.countDown();
            assertThat(releasePlan.await(10, TimeUnit.SECONDS)).isTrue();
            return List.of();
        }).when(planningService).initializePlan(any());

        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> admissionService.admit(command()));
            assertThat(planEntered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(count("task")).isZero();
            verify(taskQueue, never()).enqueue(anyLong());

            releasePlan.countDown();
            TaskAdmissionService.AdmissionResult result = future.get(10, TimeUnit.SECONDS);
            assertThat(result.created()).isTrue();
            assertThat(count("task")).isEqualTo(1);
            verify(taskQueue).enqueue(result.task().getId());
            verify(publisher).publishCreated(result.task());
        } finally {
            releasePlan.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentAdmissionsCreateOneTaskAndReuseIt() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<TaskAdmissionService.AdmissionResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return admissionService.admit(command());
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<TaskAdmissionService.AdmissionResult> results = futures.stream().map(future -> {
                try {
                    return future.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }).toList();

            assertThat(results).extracting(TaskAdmissionService.AdmissionResult::created)
                    .containsExactlyInAnyOrder(true, false);
            assertThat(results).extracting(result -> result.task().getId()).containsOnly(results.get(0).task().getId());
            assertThat(count("task")).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private TaskAdmissionService.AdmissionCommand command() {
        return new TaskAdmissionService.AdmissionCommand(1L, 7L, null, "SCRIPT", "{}");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = "org.redisson.spring.starter.RedissonAutoConfigurationV2")
    @MapperScan("com.aimanga.v2.repository")
    @Import({TaskAdmissionService.class, Mocks.class})
    static class TestApplication { }

    @TestConfiguration
    static class Mocks {
        @Bean TaskPlanningService taskPlanningService() { return mock(TaskPlanningService.class); }
        @Bean TaskQueue taskQueue() { return mock(TaskQueue.class); }
        @Bean TaskEventPublisher taskEventPublisher() { return mock(TaskEventPublisher.class); }
        @Bean ConfigService configService() {
            ConfigService config = mock(ConfigService.class);
            when(config.getInt("task_max_execution_seconds", 3600)).thenReturn(3600);
            return config;
        }
    }
}
