package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.PipelineStageItem;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.task.TaskHandler;
import com.aimanga.v2.task.TaskRuntime;
import com.aimanga.v2.service.TaskPlanningService;
import lombok.extern.slf4j.Slf4j;

/**
 * 后处理任务基类(Phase 6.6):COLORIZE / CLEAN / REPAINT 共用执行骨架。
 * 每类后处理一个独立 Stage(同 Stage 互斥锁保护),单页 Item 强制重置后由并发生图引擎执行;
 * 复用 PostProcessService,不另写独立任务模型(T6.6.3)。
 */
@Slf4j
public abstract class PostProcessTaskHandler implements TaskHandler {

    protected final PipelineContext ctx;
    protected final PostProcessService postProcessService;
    protected final PipelineStageService stageService;
    protected final ConcurrentStageRunner stageRunner;
    protected final StageItemCommitService commitService;
    protected final TaskPlanningService taskPlanningService;

    protected PostProcessTaskHandler(PipelineContext ctx, PostProcessService postProcessService,
                                     PipelineStageService stageService, ConcurrentStageRunner stageRunner,
                                     StageItemCommitService commitService, TaskPlanningService taskPlanningService) {
        this.ctx = ctx;
        this.postProcessService = postProcessService;
        this.stageService = stageService;
        this.stageRunner = stageRunner;
        this.commitService = commitService;
        this.taskPlanningService = taskPlanningService;
    }

    /** 后处理类型:COLORIZE/CLEAN/REPAINT(同时是 Stage 类型) */
    protected abstract String op();

    @Override
    public void run(TaskEntity task, TaskRuntime runtime) {
        Project project = ctx.project(task.getProjectId());
        boolean planned = taskPlanningService != null && taskPlanningService.hasPlan(task);
        Long requestedPageId = parseId(task.getPayload(), "pageId");
        java.util.List<Long> targetPageIds = planned
                ? taskPlanningService.targetIds(task, op(), "PAGE")
                : requestedPageId == null ? java.util.List.of() : java.util.List.of(requestedPageId);
        Long pageId = targetPageIds.isEmpty() ? null : targetPageIds.get(0);
        if (pageId == null) {
            throw new BusinessException(400, op() + " 任务需要在 payload 中提供 pageId");
        }
        String stageType = op();
        stageService.markRunning(project.getId(), stageType);
        // Item 可能在前次 BATCH/生成中不存在(该阶段首跑),确保存在并强制重置
        if (!planned) {
            stageService.createItems(project.getId(), stageType, "PAGE", java.util.List.of(pageId));
            stageService.forceResetItemsByBusiness(project.getId(), stageType, "PAGE", java.util.List.of(pageId));
        }

        PipelineStageService.StageItemStats before = stageService.getItemStatsInScope(
                project.getId(), stageType, "PAGE", targetPageIds);
        runtime.begin(targetPageIds.size());

        stageRunner.run(project.getId(), stageType, StageRunScope.page(pageId), runtime,
                execution -> {
                    PipelineStageItem item = execution.item();
                    PageEntity page = ctx.pageMapper.selectById(item.getBusinessId());
                    if (page == null) {
                        throw new BusinessException(404, "页面不存在: " + item.getBusinessId());
                    }
                    TaskPlanningService.PlannedPageInput input = taskPlanningService.requireCurrentPageInput(
                            execution.planUnitId(), page, true);
                    String repaintPrompt = parseString(task.getPayload(), "repaintPrompt");
                    PostProcessService.PostProcessResult result = postProcessService.execute(
                            project, page, op(), repaintPrompt,
                            parseString(task.getPayload(), "maskUrl"),
                            parseString(task.getPayload(), "colorMode"));
                    var commit = commitService.commitFenced(execution,
                            () -> postProcessService.applyResult(project, page, input.imageRevision(), result, repaintPrompt));
                    if (!commit.committed()) {
                        throw new StaleCommitRejectedException(item.getId());
                    }
                    return commit.resultRef();
                }, stageRunner.imageEngine());

        PipelineStageService.StageItemStats stats = stageService.getItemStatsInScope(
                project.getId(), stageType, "PAGE", targetPageIds);
        stageService.refreshStageTerminalState(project.getId(), stageType);
        if (stats.failed() > 0) {
            log.warn("[post-process] 作品 {} {} 任务范围内失败 {}/{}", project.getId(), op(),
                    stats.failed(), stats.total());
        }
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
