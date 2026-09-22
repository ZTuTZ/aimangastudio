package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.ExportArtifact;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.ExportArtifactMapper;
import com.aimanga.v2.repository.TaskMapper;
import com.aimanga.v2.repository.TaskPlanUnitMapper;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.storage.StorageService;
import com.aimanga.v2.task.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ExportArtifactServiceTest {

    @Test
    void downloadRequiresCompletedTaskAndUnexpiredArtifact() {
        ExportArtifactMapper artifacts = mock(ExportArtifactMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        ExportArtifact artifact = artifact(LocalDateTime.now().plusHours(1));
        TaskEntity task = new TaskEntity();
        task.setId(7L);
        task.setTaskType("EXPORT");
        task.setStatus(TaskStatus.SUCCESS);
        when(artifacts.selectById(9L)).thenReturn(artifact);
        when(tasks.selectById(7L)).thenReturn(task);

        ExportArtifactService service = service(artifacts, tasks);
        service.requireDownloadable(9L);

        task.setStatus(TaskStatus.STOPPED);
        assertThatThrownBy(() -> service.requireDownloadable(9L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("尚未成功");
    }

    @Test
    void expiredArtifactCannotBeDownloadedEvenBeforeCleanupRuns() {
        ExportArtifactMapper artifacts = mock(ExportArtifactMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        when(artifacts.selectById(9L)).thenReturn(artifact(LocalDateTime.now().minusSeconds(1)));
        TaskEntity task = new TaskEntity();
        task.setId(7L);
        task.setTaskType("EXPORT");
        task.setStatus(TaskStatus.PARTIAL);
        when(tasks.selectById(7L)).thenReturn(task);

        assertThatThrownBy(() -> service(artifacts, tasks).requireDownloadable(9L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已过期");
    }

    @Test
    void retryReplacesTheOldActiveArtifactAndDeletesOnlyTheOldFile() {
        ExportArtifactMapper artifacts = mock(ExportArtifactMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        TaskPlanUnitMapper units = mock(TaskPlanUnitMapper.class);
        StorageService storage = mock(StorageService.class);
        ConfigService config = mock(ConfigService.class);
        ExportArtifact existing = artifact(LocalDateTime.now().plusHours(1));
        existing.setStorageUrl("https://bucket/exports/final/stale-caller.zip");
        ExportArtifact current = artifact(LocalDateTime.now().plusHours(1));
        current.setStorageUrl("https://bucket/exports/final/old.zip");
        when(artifacts.selectById(9L)).thenReturn(current);
        when(config.getInt("export_artifact_ttl_hours", 168)).thenReturn(168);
        when(artifacts.activate(eq(9L), eq("https://bucket/exports/final/new.zip"),
                eq(456L), eq("new-sha"), any(LocalDateTime.class))).thenReturn(1);

        ExportArtifact updated = new ExportArtifactService(
                artifacts, tasks, units, storage, config, new ObjectMapper())
                .activate(existing, "https://bucket/exports/final/new.zip", 456L, "new-sha");

        assertThat(updated.getStorageUrl()).isEqualTo("https://bucket/exports/final/new.zip");
        assertThat(updated.getByteSize()).isEqualTo(456L);
        assertThat(updated.getSha256()).isEqualTo("new-sha");
        verify(storage).deleteStoredFile("https://bucket/exports/final/old.zip");
        verify(storage, never()).deleteStoredFile("https://bucket/exports/final/new.zip");
    }

    @Test
    void expiredArtifactCanBePublishedAgainByTheSameTask() {
        ExportArtifactMapper artifacts = mock(ExportArtifactMapper.class);
        StorageService storage = mock(StorageService.class);
        ConfigService config = mock(ConfigService.class);
        ExportArtifact expired = artifact(LocalDateTime.now().minusHours(1));
        expired.setStatus(ExportArtifact.STATUS_EXPIRED);
        expired.setStorageUrl(null);
        when(artifacts.selectById(9L)).thenReturn(expired);
        when(config.getInt("export_artifact_ttl_hours", 168)).thenReturn(168);
        when(artifacts.activate(eq(9L), eq("https://bucket/exports/final/rebuilt.zip"),
                eq(789L), eq("rebuilt-sha"), any(LocalDateTime.class))).thenReturn(1);

        ExportArtifact updated = new ExportArtifactService(artifacts, mock(TaskMapper.class),
                mock(TaskPlanUnitMapper.class), storage, config, new ObjectMapper())
                .activate(expired, "https://bucket/exports/final/rebuilt.zip", 789L, "rebuilt-sha");

        assertThat(updated.getStatus()).isEqualTo(ExportArtifact.STATUS_ACTIVE);
        assertThat(updated.getStorageUrl()).endsWith("/rebuilt.zip");
        verifyNoInteractions(storage);
    }

    @Test
    void expiryCleanupPreservesCheckpointsNeededByTaskRetry() {
        ExportArtifactMapper artifacts = mock(ExportArtifactMapper.class);
        TaskPlanUnitMapper units = mock(TaskPlanUnitMapper.class);
        StorageService storage = mock(StorageService.class);
        ExportArtifact expired = artifact(LocalDateTime.now().minusHours(1));
        when(artifacts.selectCleanupCandidates()).thenReturn(List.of(expired));
        when(artifacts.markExpired(expired.getId(), expired.getStorageUrl())).thenReturn(1);

        new ExportArtifactService(artifacts, mock(TaskMapper.class), units,
                storage, mock(ConfigService.class), new ObjectMapper())
                .cleanupExpiredArtifacts();

        verify(storage).deleteStoredFile(expired.getStorageUrl());
        verify(artifacts).markExpired(expired.getId(), expired.getStorageUrl());
        verifyNoInteractions(units);
    }

    private static ExportArtifactService service(ExportArtifactMapper artifacts, TaskMapper tasks) {
        return new ExportArtifactService(artifacts, tasks, mock(TaskPlanUnitMapper.class),
                mock(StorageService.class), mock(ConfigService.class), new ObjectMapper());
    }

    private static ExportArtifact artifact(LocalDateTime expiresAt) {
        ExportArtifact artifact = new ExportArtifact();
        artifact.setId(9L);
        artifact.setTaskId(7L);
        artifact.setStatus(ExportArtifact.STATUS_ACTIVE);
        artifact.setStorageUrl("https://bucket/exports/final/a.zip");
        artifact.setByteSize(123L);
        artifact.setExpiresAt(expiresAt);
        return artifact;
    }
}
