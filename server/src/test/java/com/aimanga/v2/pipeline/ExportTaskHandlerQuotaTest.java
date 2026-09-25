package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.ExportStoredObject;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.model.TaskPlanUnit;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.service.TaskPlanningService;
import com.aimanga.v2.storage.StorageService;
import com.aimanga.v2.task.OperationalMetrics;
import com.aimanga.v2.task.TaskRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportTaskHandlerQuotaTest {
    private static final int SIX_MIB = 6 * 1024 * 1024;

    @Test
    void aggregateCheckpointBytesOverQuotaAbortWithoutPublishingDownload() throws Exception {
        TaskPlanningService planning = mock(TaskPlanningService.class);
        PipelineStageService stages = mock(PipelineStageService.class);
        ConcurrentStageRunner runner = mock(ConcurrentStageRunner.class);
        PublicationService publication = mock(PublicationService.class);
        StorageService storage = mock(StorageService.class);
        ConfigService config = mock(ConfigService.class);
        OperationalMetrics metrics = mock(OperationalMetrics.class);
        StageItemCommitService commit = mock(StageItemCommitService.class);
        ExportArtifactService artifacts = mock(ExportArtifactService.class);
        ExportObjectService objects = mock(ExportObjectService.class);
        TaskRuntime runtime = mock(TaskRuntime.class);
        ObjectMapper json = new ObjectMapper();
        ExportTempFiles temp = new ExportTempFiles(config);
        when(config.getLong(eq("export_max_bytes"), anyLong())).thenReturn(10L * 1024 * 1024);
        when(config.getLong(eq("export_temp_max_bytes"), anyLong())).thenReturn(64L * 1024 * 1024);

        byte[] zeros = new byte[SIX_MIB];
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zeros));
        TaskPlanUnit first = checkpoint(1L, 7L, SIX_MIB, sha, json);
        TaskPlanUnit second = checkpoint(2L, 8L, SIX_MIB, sha, json);
        TaskEntity task = new TaskEntity();
        task.setId(70L);
        task.setProjectId(7L);
        task.setUserId(1L);
        when(planning.targetIds(task, PipelineStageService.STAGE_EXPORT, "PROJECT")).thenReturn(List.of(7L, 8L));
        when(planning.plan(task)).thenReturn(List.of(first, second));
        when(artifacts.beginAttempt(any())).thenReturn(new ExportArtifactService.ExportAttempt(90L, 70L, 1, "owner"));
        doAnswer(invocation -> {
            OutputStream target = invocation.getArgument(1);
            target.write(zeros);
            return null;
        }).when(objects).writeTo(anyLong(), any(OutputStream.class));
        ExportStoredObject finalObject = new ExportStoredObject();
        finalObject.setId(501L);
        when(objects.registerUpload(eq(70L), eq(null), eq(90L), eq(1L),
                eq(ExportStoredObject.KIND_FINAL), eq("owner"), eq(1L))).thenReturn(finalObject);

        ExportTaskHandler handler = new ExportTaskHandler(planning, stages, runner, publication, storage,
                config, json, metrics, commit, artifacts, objects, temp);

        assertThatThrownBy(() -> handler.run(task, runtime))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("导出大小超过限制");
        verify(artifacts, never()).publish(any(), anyLong(), any());
    }

    private static TaskPlanUnit checkpoint(Long id, Long projectId, long bytes, String sha,
                                           ObjectMapper json) {
        TaskPlanUnit unit = new TaskPlanUnit();
        unit.setId(id);
        unit.setBusinessId(projectId);
        unit.setStageType(PipelineStageService.STAGE_EXPORT);
        unit.setStatus(TaskPlanUnit.STATUS_SUCCESS);
        unit.setResultRef(json.createObjectNode()
                .put("objectId", id + 100).put("bytes", bytes).put("sha256", sha).toString());
        return unit;
    }
}
