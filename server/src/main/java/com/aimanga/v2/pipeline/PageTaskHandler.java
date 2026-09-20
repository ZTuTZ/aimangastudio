package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.GenerationRecord;
import com.aimanga.v2.model.PipelineStageItem;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.task.TaskHandler;
import com.aimanga.v2.task.TaskRuntime;
import com.aimanga.v2.service.TaskPlanningService;
import lombok.RequiredArgsConstructor;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PAGE 单页成品重生成任务(Phase 6.5 T6.5.3,执行原则3:单页独立重生成才创建 PAGE Task):
 * - payload {"pageId":x, "colorMode":"partial"} → 强制重画该页成品(旧图保留进 generate_records);
 * - 复用 PageGenerationService,不另写 Prompt/参考逻辑;
 * - 若同项目已有活跃 BATCH/PAGE 在跑,创建层复用语义 + Stage 执行互斥锁保证不并发抢 Item。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PageTaskHandler implements TaskHandler {

    public static final String TYPE = "PAGE";
    private static final String BUSINESS_TYPE_PAGE = "PAGE";

    private final PipelineContext ctx;
    private final PageGenerationService pageGenerationService;
    private final PipelineStageService stageService;
    private final ConcurrentStageRunner stageRunner;
    private final StageItemCommitService commitService;
    private final GenerationRecordService generationRecordService;
    private final ProjectCompletionService projectCompletionService;
    private final TaskPlanningService taskPlanningService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(TaskEntity task, TaskRuntime runtime) {
        Project project = ctx.project(task.getProjectId());
        boolean planned = taskPlanningService.hasPlan(task);
        Long requestedPageId = parseId(task.getPayload(), "pageId");
        List<Long> targetPageIds = planned
                ? taskPlanningService.targetIds(task, PipelineStageService.STAGE_IMAGE, BUSINESS_TYPE_PAGE)
                : requestedPageId == null ? List.of() : List.of(requestedPageId);
        Long pageId = targetPageIds.isEmpty() ? null : targetPageIds.get(0);
        if (pageId == null) {
            throw new BusinessException(400, "PAGE 任务需要在 payload 中提供 pageId");
        }
        String colorMode = parseString(task.getPayload(), "colorMode");

        stageService.markRunning(project.getId(), PipelineStageService.STAGE_IMAGE);
        if (!planned) {
            stageService.createItems(project.getId(), PipelineStageService.STAGE_IMAGE, BUSINESS_TYPE_PAGE, List.of(pageId));
            stageService.forceResetItemsByBusiness(project.getId(), PipelineStageService.STAGE_IMAGE,
                    BUSINESS_TYPE_PAGE, List.of(pageId));
        }

        PipelineStageService.StageItemStats before = stageService.getItemStatsInScope(
                project.getId(), PipelineStageService.STAGE_IMAGE, BUSINESS_TYPE_PAGE, targetPageIds);
        runtime.begin(targetPageIds.size());

        stageRunner.run(project.getId(), PipelineStageService.STAGE_IMAGE, StageRunScope.page(pageId), runtime,
                execution -> {
                    PipelineStageItem item = execution.item();
                    PageEntity page = ctx.pageMapper.selectById(item.getBusinessId());
                    if (page == null) {
                        throw new BusinessException(404, "页面不存在: " + item.getBusinessId());
                    }
                    TaskPlanningService.PlannedPageInput input = taskPlanningService.requireCurrentPageInput(
                            execution.planUnitId(), page, true);
                    if (!commitService.runWhileOwned(execution, () -> pageGenerationService.markPageRunning(page.getId()))) {
                        throw new StaleCommitRejectedException(item.getId());
                    }
                    try {
                        PageGenerationService.PageGenResult result = pageGenerationService.processPage(
                                project, page, colorMode, true);
                        var commit = commitService.commitFenced(execution, () -> {
                            String ref = pageGenerationService.applyPageImageResult(
                                    page.getId(), input.scriptVersion(), input.imageRevision(), result,
                                    page.getGenerateRecords());
                            generationRecordService.record(project.getId(), page.getChapterId(), page.getId(), task.getId(),
                                    GenerationRecord.KIND_PAGE, ctx.configService.getString("ai_image_model"),
                                    result.prompt(), result.images(), result.inputUrl(), result.url(),
                                    GenerationRecord.STATUS_SUCCESS, null);
                            return ref;
                        });
                        if (!commit.committed()) {
                            throw new StaleCommitRejectedException(item.getId());
                        }
                        return commit.resultRef();
                    } catch (StaleCommitRejectedException | ContentVersionConflictException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        commitService.runWhileOwned(execution,
                                () -> pageGenerationService.markPageFailed(page.getId(), page.getGenerateRecords(), colorMode, reason));
                        throw e;
                    }
                }, stageRunner.imageEngine());

        PipelineStageService.StageItemStats stats = stageService.getItemStatsInScope(
                project.getId(), PipelineStageService.STAGE_IMAGE, BUSINESS_TYPE_PAGE, targetPageIds);
        if (stats.failed() == 0) {
            stageService.updateStageProgress(project.getId(), PipelineStageService.STAGE_IMAGE);
        }
        PageEntity latest = ctx.pageMapper.selectById(pageId);
        if (latest != null) {
            projectCompletionService.recalculateChapter(latest.getChapterId());
        }
        projectCompletionService.recalculateProject(project.getId());
    }

    private Long parseId(String payload, String field) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                            payload == null || payload.isBlank() ? "{}" : payload);
            return node.has(field) && node.get(field).canConvertToLong() ? node.get(field).asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String parseString(String payload, String field) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                            payload == null || payload.isBlank() ? "{}" : payload);
            return node.has(field) && node.get(field).isTextual() ? node.get(field).asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
