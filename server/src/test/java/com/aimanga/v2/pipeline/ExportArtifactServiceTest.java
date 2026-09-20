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
