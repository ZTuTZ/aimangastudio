package com.aimanga.v2.integration;

import com.aimanga.v2.model.ExportStoredObject;
import com.aimanga.v2.pipeline.ExportArtifactService;
import com.aimanga.v2.pipeline.ExportObjectService;
import com.aimanga.v2.repository.ExportArtifactMapper;
import com.aimanga.v2.repository.ExportStoredObjectMapper;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.storage.StorageService;
import com.aimanga.v2.task.TaskExecutionOwner;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = ExportLifecycleIT.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ExportLifecycleIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("aimanga_export").withUsername("aimanga").withPassword("aimanga");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired ExportArtifactService artifactService;
    @Autowired ExportObjectService objectService;
    @Autowired ExportArtifactMapper artifactMapper;
    @Autowired ExportStoredObjectMapper objectMapper;
    @Autowired StorageService storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void seed() {
        reset(storage);
        when(storage.exportUrl(anyString())).thenAnswer(invocation -> "https://bucket/" + invocation.getArgument(0));
        jdbc.update("DELETE FROM export_object_read_lease");
        jdbc.update("DELETE FROM export_stored_object");
        jdbc.update("DELETE FROM export_artifact");
        jdbc.update("DELETE FROM task_plan_unit");
        jdbc.update("DELETE FROM task");
        jdbc.update("DELETE FROM project");
        jdbc.update("INSERT INTO project(id,content_uid,user_id,title,pause_requested,control_version) " +
                "VALUES (7,'00000000-0000-0000-0000-000000000007',1,'导出测试',0,0)");
        jdbc.update("INSERT INTO task(id,user_id,project_id,task_type,status,claim_token,lease_until,payload,plan_version,plan_initialized_at) " +
                "VALUES (70,1,7,'EXPORT',1,'owner-a',DATE_ADD(NOW(),INTERVAL 1 HOUR),'{}',1,NOW())");
    }

    @Test
    void newerOwnerPublishesAndStaleOwnerCannotReplaceItsObjectOrResult() throws Exception {
        var attemptA = artifactService.beginAttempt(new TaskExecutionOwner(70L, "owner-a"));
        ExportStoredObject objectA = upload(attemptA, "a");

        jdbc.update("UPDATE task SET claim_token='owner-b', lease_until=DATE_ADD(NOW(),INTERVAL 1 HOUR) WHERE id=70");
        var attemptB = artifactService.beginAttempt(new TaskExecutionOwner(70L, "owner-b"));
        ExportStoredObject objectB = upload(attemptB, "b");
        var summary = json.createObjectNode().put("success", 2).put("failed", 0).put("total", 2);
        artifactService.publish(attemptB, objectB.getId(), summary);

        assertThatThrownBy(() -> artifactService.publish(attemptA, objectA.getId(), json.createObjectNode()))
                .isInstanceOf(com.aimanga.v2.common.BusinessException.class)
                .hasMessageContaining("所有权");
        var artifact = artifactMapper.selectByTaskId(70L);
        assertThat(artifact.getGeneration()).isEqualTo(attemptB.generation());
        assertThat(artifact.getCurrentObjectId()).isEqualTo(objectB.getId());
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(result,'$.sha256')) FROM task WHERE id=70", String.class))
                .isEqualTo(objectB.getSha256());
        assertThat(objectMapper.selectById(objectA.getId()).getState()).isEqualTo(ExportStoredObject.STATE_RETAINED);
    }

    @Test
    void deletingTaskDoesNotCascadeObjectTrackingRow() throws Exception {
        var attempt = artifactService.beginAttempt(new TaskExecutionOwner(70L, "owner-a"));
        ExportStoredObject object = upload(attempt, "orphan");
        objectService.requestTaskDeletion(70L, "test delete");
        jdbc.update("DELETE FROM task WHERE id=70");

        assertThat(objectMapper.selectById(object.getId())).isNotNull();
        assertThat(objectMapper.selectById(object.getId()).getState())
                .isEqualTo(ExportStoredObject.STATE_DELETE_PENDING);
    }

    @Test
    void staleUploadCleanupCandidateCannotBeClaimedAfterTaskResumes() {
        ExportStoredObject object = objectService.registerUpload(70L, null, null, 1,
                ExportStoredObject.KIND_FINAL, "owner-a", 1L);
        jdbc.update("UPDATE export_stored_object SET upload_deadline=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?",
                object.getId());
        jdbc.update("UPDATE task SET status=3 WHERE id=70");
        assertThat(objectMapper.selectCleanupCandidates())
                .extracting(ExportStoredObject::getId).contains(object.getId());

        jdbc.update("UPDATE task SET status=1 WHERE id=70");
        assertThat(objectMapper.claimCleanup(object.getId(), UUID.randomUUID().toString(), 300)).isZero();
        assertThat(objectMapper.selectById(object.getId()).getState())
                .isEqualTo(ExportStoredObject.STATE_UPLOADING);
    }

    @Test
    void cleanupWaitsForActiveDownloadAndDeletesAfterReadLeaseEnds() throws Exception {
        var attempt = artifactService.beginAttempt(new TaskExecutionOwner(70L, "owner-a"));
        ExportStoredObject object = upload(attempt, "download");
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch finishRead = new CountDownLatch(1);
        doAnswer(invocation -> {
            readStarted.countDown();
            if (!finishRead.await(10, TimeUnit.SECONDS)) throw new AssertionError("download did not resume");
            java.io.OutputStream target = invocation.getArgument(1);
            target.write("download".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return null;
        }).when(storage).writeExportFile(org.mockito.ArgumentMatchers.eq(object.getStorageKey()),
                org.mockito.ArgumentMatchers.any(java.io.OutputStream.class));

        ByteArrayOutputStream received = new ByteArrayOutputStream();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var download = executor.submit(() -> objectService.streamWithLease(object.getId(), received));
            assertThat(readStarted.await(10, TimeUnit.SECONDS)).isTrue();
            objectService.requestDeletion(object.getId(), "expired while downloading", 0);
            jdbc.update("UPDATE export_stored_object SET delete_after=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?",
                    object.getId());
            objectService.cleanup();

            assertThat(objectMapper.selectById(object.getId()).getState())
                    .isEqualTo(ExportStoredObject.STATE_DELETE_PENDING);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM export_object_read_lease WHERE object_id=?",
                    Integer.class, object.getId())).isEqualTo(1);

            finishRead.countDown();
            download.get(10, TimeUnit.SECONDS);
            assertThat(received.toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("download");
            objectService.cleanup();
            assertThat(objectMapper.selectById(object.getId()).getState())
                    .isEqualTo(ExportStoredObject.STATE_DELETED);
        } finally {
            finishRead.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cleanupReclaimsObjectAfterRemoteDeleteButBeforeDatabaseAcknowledgement() throws Exception {
        var attempt = artifactService.beginAttempt(new TaskExecutionOwner(70L, "owner-a"));
        ExportStoredObject object = upload(attempt, "crash-window");
        objectService.requestDeletion(object.getId(), "expired", 0);
        jdbc.update("UPDATE export_stored_object SET delete_after=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?",
                object.getId());

        assertThat(objectMapper.claimCleanup(object.getId(), "crashed-worker", 300)).isEqualTo(1);
        storage.deleteExportFile(object.getStorageKey());
        assertThat(objectMapper.selectById(object.getId()).getState())
                .isEqualTo(ExportStoredObject.STATE_DELETING);

        jdbc.update("UPDATE export_stored_object SET cleanup_lease_until=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?",
                object.getId());
        objectService.cleanup();

        assertThat(objectMapper.selectById(object.getId()).getState())
                .isEqualTo(ExportStoredObject.STATE_DELETED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM export_stored_object WHERE id=?",
                Integer.class, object.getId())).isEqualTo(1);
    }

    @Test
    void legacyFinalBackfillIsIdempotentAcrossRepeatedRuns() {
        String url = "https://bucket/exports/final/legacy.zip";
        when(storage.exportKey(url)).thenReturn("exports/final/legacy.zip");
        jdbc.update("INSERT INTO export_artifact(task_id,user_id,storage_url,file_name,byte_size,sha256,status,expires_at) " +
                "VALUES (70,1,?,'legacy.zip',10,?,1,DATE_ADD(NOW(),INTERVAL 1 DAY))",
                url, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        objectService.backfillLegacyObjects();
        objectService.backfillLegacyObjects();

        Long objectId = jdbc.queryForObject("SELECT current_object_id FROM export_artifact WHERE task_id=70", Long.class);
        assertThat(objectId).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM export_stored_object WHERE storage_key='exports/final/legacy.zip'",
                Integer.class)).isEqualTo(1);
        assertThat(objectMapper.selectById(objectId).getState()).isEqualTo(ExportStoredObject.STATE_RETAINED);
    }

    private ExportStoredObject upload(ExportArtifactService.ExportAttempt attempt, String marker) throws Exception {
        var file = Files.createTempFile("export-life-", ".zip");
        try {
            Files.writeString(file, marker);
            ExportStoredObject object = objectService.registerUpload(70L, null, attempt.artifactId(),
                    attempt.generation(), ExportStoredObject.KIND_FINAL, attempt.claimToken(), 1L);
            return objectService.upload(object, file, Files.size(file), marker + "-sha");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.redisson.spring.starter.RedissonAutoConfigurationV2",
            "org.apache.shiro.spring.boot.autoconfigure.ShiroAutoConfiguration"
    })
    @MapperScan("com.aimanga.v2.repository")
    @Import({ExportArtifactService.class, ExportObjectService.class, Mocks.class})
    static class TestApplication { }

    @TestConfiguration
    static class Mocks {
        @Bean StorageService storageService() { return mock(StorageService.class); }
        @Bean ConfigService configService() {
            ConfigService config = mock(ConfigService.class);
            when(config.getInt("export_upload_timeout_seconds", 3600)).thenReturn(3600);
            when(config.getInt("export_artifact_ttl_hours", 168)).thenReturn(168);
            when(config.getInt("export_download_max_seconds", 3600)).thenReturn(3600);
            when(config.getInt("export_checkpoint_ttl_hours", 168)).thenReturn(168);
            return config;
        }
    }
}
