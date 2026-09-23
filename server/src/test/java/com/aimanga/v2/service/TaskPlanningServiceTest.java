package com.aimanga.v2.service;

import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.model.TaskPlanUnit;
import com.aimanga.v2.pipeline.PipelineStageService;
import com.aimanga.v2.pipeline.PublicationService;
import com.aimanga.v2.dto.export.ComicManifest;
import com.aimanga.v2.repository.AssetMapper;
import com.aimanga.v2.repository.ChapterMapper;
import com.aimanga.v2.repository.PageMapper;
import com.aimanga.v2.repository.TaskMapper;
import com.aimanga.v2.repository.TaskPlanUnitMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class TaskPlanningServiceTest {

    @Test
    void singlePageLayoutCreatesOnlyTheRequestedPlanUnit() {
        TaskPlanUnitMapper unitMapper = mock(TaskPlanUnitMapper.class);
        PageMapper pageMapper = mock(PageMapper.class);
        PageEntity requested = new PageEntity();
        requested.setId(42L);
        requested.setProjectId(7L);
        requested.setChapterId(8L);
        requested.setScriptVersion(3);
        when(pageMapper.selectById(42L)).thenReturn(requested);

        TaskPlanningService service = new TaskPlanningService(
                unitMapper, mock(TaskMapper.class), pageMapper, mock(ChapterMapper.class),
                mock(AssetMapper.class), mock(PipelineStageService.class), new ObjectMapper(),
                mock(ConfigService.class), mock(PublicationService.class), mock(com.aimanga.v2.repository.ExportStoredObjectMapper.class));
        TaskEntity task = task(11L, 7L, "LAYOUT", "{\"pageId\":42,\"force\":true}");

        List<TaskPlanUnit> units = service.buildUnits(task);

        assertThat(units).singleElement().satisfies(unit -> {
            assertThat(unit.getTaskId()).isEqualTo(11L);
            assertThat(unit.getPlanVersion()).isEqualTo(1);
            assertThat(unit.getStageType()).isEqualTo("LAYOUT");
            assertThat(unit.getBusinessType()).isEqualTo("PAGE");
            assertThat(unit.getBusinessId()).isEqualTo(42L);
            assertThat(unit.getInputSnapshot()).contains("\"force\":true");
            assertThat(unit.getInputSnapshot()).contains("\"scriptVersion\":3");
        });
    }

    @Test
    void pageOutsideProjectIsRejectedBeforeAnyPlanMutation() {
        TaskPlanUnitMapper unitMapper = mock(TaskPlanUnitMapper.class);
        PageMapper pageMapper = mock(PageMapper.class);
        PageEntity foreign = new PageEntity();
        foreign.setId(42L);
        foreign.setProjectId(99L);
        when(pageMapper.selectById(42L)).thenReturn(foreign);

        TaskPlanningService service = new TaskPlanningService(
                unitMapper, mock(TaskMapper.class), pageMapper, mock(ChapterMapper.class),
                mock(AssetMapper.class), mock(PipelineStageService.class), new ObjectMapper(),
                mock(ConfigService.class), mock(PublicationService.class), mock(com.aimanga.v2.repository.ExportStoredObjectMapper.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.buildUnits(task(11L, 7L, "PAGE", "{\"pageId\":42}")))
                .isInstanceOf(com.aimanga.v2.common.BusinessException.class);
        org.mockito.Mockito.verify(unitMapper, org.mockito.Mockito.never()).insert(any(TaskPlanUnit.class));
    }

    @Test
    void exportPlanCapturesAnImmutablePublicationSnapshot() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PublicationService publicationService = mock(PublicationService.class);
        when(publicationService.buildManifest(7L)).thenReturn(new ComicManifest(
                ComicManifest.SCHEMA_VERSION, "uid-7", "作品", "", "", "cover", "漫画",
                List.of("剧情"), 1, "3:4", "color", true, List.of()));
        ConfigService configService = mock(ConfigService.class);
        when(configService.getInt("export_max_projects", 50)).thenReturn(50);
        TaskPlanningService service = new TaskPlanningService(
                mock(TaskPlanUnitMapper.class), mock(TaskMapper.class), mock(PageMapper.class),
                mock(ChapterMapper.class), mock(AssetMapper.class), mock(PipelineStageService.class),
                objectMapper, configService, publicationService, mock(com.aimanga.v2.repository.ExportStoredObjectMapper.class));

        List<TaskPlanUnit> units = service.buildUnits(task(12L, 7L, "EXPORT", "{\"projectIds\":[7]}"));

        assertThat(units).singleElement().satisfies(unit -> {
            try {
                var input = objectMapper.readTree(unit.getInputSnapshot());
                assertThat(input.path("publicationSnapshot").path("contentUid").asText()).isEqualTo("uid-7");
                assertThat(input.path("snapshotSha256").asText()).hasSize(64);
                assertThat(input.path("force").asBoolean()).isTrue();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void retryReopensSuccessfulExportUnitWhenTrackedCheckpointWasDeleted() {
        TaskPlanUnitMapper units = mock(TaskPlanUnitMapper.class);
        PipelineStageService stages = mock(PipelineStageService.class);
        var objects = mock(com.aimanga.v2.repository.ExportStoredObjectMapper.class);
        TaskPlanUnit unit = new TaskPlanUnit();
        unit.setId(88L);
        unit.setBusinessId(9L);
        unit.setStatus(TaskPlanUnit.STATUS_SUCCESS);
        unit.setResultRef("{\"objectId\":55}");
        when(units.selectPlan(12L, 1)).thenReturn(List.of(unit));
        when(objects.selectById(55L)).thenReturn(null);
        when(units.reopenSuccessfulUnit(88L)).thenReturn(1);
        TaskPlanningService service = new TaskPlanningService(
                units, mock(TaskMapper.class), mock(PageMapper.class), mock(ChapterMapper.class),
                mock(AssetMapper.class), stages, new ObjectMapper(), mock(ConfigService.class),
                mock(PublicationService.class), objects);
        TaskEntity task = task(12L, 7L, "EXPORT", "{\"projectIds\":[9]}");

        assertThat(service.reopenMissingExportCheckpoints(task)).isEqualTo(1);

        verify(units).reopenSuccessfulUnit(88L);
        verify(stages).forceResetItemsByBusiness(7L, "EXPORT", "PROJECT", List.of(9L));
    }

    private static TaskEntity task(Long id, Long projectId, String type, String payload) {
        TaskEntity task = new TaskEntity();
        task.setId(id);
        task.setProjectId(projectId);
        task.setTaskType(type);
        task.setPayload(payload);
        task.setPlanVersion(1);
        return task;
    }
}
