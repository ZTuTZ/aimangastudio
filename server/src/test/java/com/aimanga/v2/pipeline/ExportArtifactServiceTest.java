package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.ExportArtifact;
import com.aimanga.v2.model.ExportStoredObject;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.ExportArtifactMapper;
import com.aimanga.v2.repository.ExportStoredObjectMapper;
import com.aimanga.v2.repository.TaskMapper;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.task.TaskExecutionOwner;
import com.aimanga.v2.task.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportArtifactServiceTest {
    @Test
    void downloadRequiresCompletedTaskAndRetainedCurrentObject() {
        ExportArtifactMapper artifacts = mock(ExportArtifactMapper.class);
        ExportStoredObjectMapper objects = mock(ExportStoredObjectMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        ExportObjectService objectService = mock(ExportObjectService.class);
        ExportArtifact artifact = artifact();
        TaskEntity task = task(TaskStatus.SUCCESS, "owner");
        ExportStoredObject object = storedObject();
        when(artifacts.selectById(9L)).thenReturn(artifact);
        when(tasks.selectById(7L)).thenReturn(task);
        when(objectService.requireRetained(33L)).thenReturn(object);

        ExportArtifactService.Download download = service(artifacts, objects, tasks, objectService)
                .requireDownloadable(9L);

        assertThat(download.object()).isSameAs(object);
        task.setStatus(TaskStatus.STOPPED);
        assertThatThrownBy(() -> service(artifacts, objects, tasks, objectService).requireDownloadable(9L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("尚未成功");
    }

    @Test
    void beginAttemptReusesGenerationForSameOwnerAndAdvancesForNewOwner() {
        ExportArtifactMapper artifacts = mock(ExportArtifactMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        TaskEntity task = task(TaskStatus.RUNNING, "new-owner");
        ExportArtifact artifact = artifact();
        artifact.setGeneration(4L);
        artifact.setPublisherToken("old-owner");
        when(tasks.lockActiveClaim(7L, "new-owner")).thenReturn(task);
        when(artifacts.lockByTaskId(7L)).thenReturn(artifact);
        when(artifacts.beginGeneration(9L, "new-owner")).thenReturn(1);
        ExportArtifact advanced = artifact();
        advanced.setGeneration(5L);
        advanced.setPublisherToken("new-owner");
        when(artifacts.lockById(9L)).thenReturn(advanced);

        var attempt = service(artifacts, mock(ExportStoredObjectMapper.class), tasks,
                mock(ExportObjectService.class)).beginAttempt(new TaskExecutionOwner(7L, "new-owner"));

        assertThat(attempt.generation()).isEqualTo(5);
        assertThat(attempt.claimToken()).isEqualTo("new-owner");
    }

    @Test
    void stalePublisherCannotReplaceCurrentObjectOrTaskResult() {
        ExportArtifactMapper artifacts = mock(ExportArtifactMapper.class);
        ExportStoredObjectMapper objects = mock(ExportStoredObjectMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        when(tasks.lockActiveClaim(7L, "stale")).thenReturn(null);
        var service = service(artifacts, objects, tasks, mock(ExportObjectService.class));

        assertThatThrownBy(() -> service.publish(
                new ExportArtifactService.ExportAttempt(9L, 7L, 2L, "stale"),
                44L, new ObjectMapper().createObjectNode()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("所有权");

        verify(artifacts, never()).publishOwned(any(), eq(2L), eq("stale"), any(), any(), any(Long.class), any(), any());
        verify(tasks, never()).updateResultOwned(any(), any(), any());
    }

    private static ExportArtifactService service(ExportArtifactMapper artifacts,
                                                  ExportStoredObjectMapper objects,
                                                  TaskMapper tasks,
                                                  ExportObjectService objectService) {
        ConfigService config = mock(ConfigService.class);
        when(config.getInt("export_artifact_ttl_hours", 168)).thenReturn(168);
        return new ExportArtifactService(artifacts, objects, tasks, objectService, config,
                mock(org.springframework.transaction.PlatformTransactionManager.class));
    }

    private static ExportArtifact artifact() {
        ExportArtifact artifact = new ExportArtifact();
        artifact.setId(9L);
        artifact.setTaskId(7L);
        artifact.setStatus(ExportArtifact.STATUS_ACTIVE);
        artifact.setGeneration(1L);
        artifact.setPublisherToken("owner");
        artifact.setCurrentObjectId(33L);
        artifact.setStorageUrl("https://bucket/exports/final/a.zip");
        artifact.setByteSize(123L);
        artifact.setExpiresAt(LocalDateTime.now().plusHours(1));
        return artifact;
    }

    private static TaskEntity task(int status, String owner) {
        TaskEntity task = new TaskEntity();
        task.setId(7L);
        task.setTaskType("EXPORT");
        task.setStatus(status);
        task.setClaimToken(owner);
        return task;
    }

    private static ExportStoredObject storedObject() {
        ExportStoredObject object = new ExportStoredObject();
        object.setId(33L);
        object.setState(ExportStoredObject.STATE_RETAINED);
        object.setStorageKey("exports/final/1/a.zip");
        return object;
    }
}
