package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.GenerationRecord;
import com.aimanga.v2.model.PipelineStage;
import com.aimanga.v2.model.PipelineStageItem;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.task.TaskHandler;
import com.aimanga.v2.task.TaskRuntime;
import com.aimanga.v2.service.TaskPlanningService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LAYOUT 布局图任务(Phase 6.2 T6.2.4):
 * 一个 LAYOUT Task 处理项目/话内全部 PENDING 页 Items(businessType=PAGE),禁止 1 页 = 1 Task。
 * - 并发执行走 ConcurrentStageRunner(生图引擎);
 * - 幂等:已有布局图且非 force 的页直接补标成功,不重复调用 AI(T6.2.5);
 * - payload {"pageId":x} → 单页强制重布局(手动重生成);{"chapterId":y} → 按话;空 → 整部缺布局图的页;
 * - 页被 SCRIPT 重建后旧 Item 成为孤儿,同步时按当前页清理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LayoutTaskHandler implements TaskHandler {

    public static final String TYPE = "LAYOUT";
    private static final String BUSINESS_TYPE_PAGE = "PAGE";

    private final PipelineContext ctx;
    private final LayoutGenerationService layoutGenerationService;
    private final PipelineStageService stageService;
    private final ConcurrentStageRunner stageRunner;
    private final StageItemCommitService commitService;
    private final GenerationRecordService generationRecordService;
    private final TaskPlanningService taskPlanningService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(TaskEntity task, TaskRuntime runtime) {
        Project project = ctx.project(task.getProjectId());
        Long pageId = parseId(task.getPayload(), "pageId");
        Long chapterId = parseId(task.getPayload(), "chapterId");
        stageService.markRunning(project.getId(), PipelineStageService.STAGE_LAYOUT);

        boolean planned = taskPlanningService.hasPlan(task);
        if (!planned) syncItems(project, chapterId, pageId == null && chapterId == null);
        if (!planned && pageId != null) {
            // 手动单页重布局(T6.5.2):确保 Item 存在(已有布局图的页平时不建 Item)并强制重置
            stageService.createItems(project.getId(), PipelineStageService.STAGE_LAYOUT, BUSINESS_TYPE_PAGE, List.of(pageId));
            stageService.forceResetItemsByBusiness(project.getId(), PipelineStageService.STAGE_LAYOUT,
                    BUSINESS_TYPE_PAGE, List.of(pageId));
        }

        List<Long> targetPageIds = planned
                ? taskPlanningService.targetIds(task, PipelineStageService.STAGE_LAYOUT, BUSINESS_TYPE_PAGE)
                : legacyTargetPageIds(project.getId(), pageId, chapterId);
        PipelineStageService.StageItemStats before = stageService.getItemStatsInScope(
                project.getId(), PipelineStageService.STAGE_LAYOUT, BUSINESS_TYPE_PAGE, targetPageIds);
        if (before.total() == 0 || before.pending() == 0) {
            stageService.refreshStageTerminalState(project.getId(), PipelineStageService.STAGE_LAYOUT);
            return;
        }
        runtime.begin(targetPageIds.size());

        // Phase 8.2:scoped run —— 单页/按话任务只允许执行目标页的 Item
        StageRunScope scope = StageRunScope.pages(targetPageIds);
        stageRunner.run(project.getId(), PipelineStageService.STAGE_LAYOUT, scope, runtime,
                execution -> processOnePage(project, execution, task.getId()), stageRunner.imageEngine());

        if (stageService.isStagePaused(project.getId(), PipelineStageService.STAGE_LAYOUT)) {
            log.info("[layout] 作品 {} LAYOUT 暂停中,等待继续", project.getId());
            return;
        }

        int stageStatus = stageService.refreshStageTerminalState(project.getId(), PipelineStageService.STAGE_LAYOUT);
        PipelineStageService.StageItemStats stats = stageService.getItemStatsInScope(
                project.getId(), PipelineStageService.STAGE_LAYOUT, BUSINESS_TYPE_PAGE, targetPageIds);
        if (stageStatus == PipelineStage.STATUS_SUCCESS) {
            log.info("[layout] 作品 {} LAYOUT 完成: {}/{}", project.getId(), stats.success(), stats.total());
        } else if (stageStatus == PipelineStage.STATUS_FAILED) {
            log.warn("[layout] 作品 {} LAYOUT 存在失败: {}/{}", project.getId(), stats.failed(), stats.total());
        }
    }

    private List<Long> legacyTargetPageIds(Long projectId, Long pageId, Long chapterId) {
        if (pageId != null) return List.of(pageId);
        return ctx.pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                        .eq(PageEntity::getProjectId, projectId)
                        .eq(chapterId != null, PageEntity::getChapterId, chapterId))
                .stream().map(PageEntity::getId).toList();
    }

    /** 单页处理(Item → AI+OSS → fenced commit 写布局图,Phase 8.1) */
    private String processOnePage(Project project, StageItemExecution execution, Long taskId) {
        PageEntity page = layoutGenerationService.page(execution.item().getBusinessId());
        TaskPlanningService.PlannedPageInput input = taskPlanningService.requireCurrentPageInput(
                execution.planUnitId(), page, false);
        boolean force = PipelineStageService.isForceRequested(execution.item());
        LayoutGenerationService.LayoutResult result = layoutGenerationService.processPage(project, page, force);
        var commit = commitService.commitFenced(execution, () -> {
            layoutGenerationService.applyLayoutResult(page.getId(), input.scriptVersion(), result);
            generationRecordService.record(project.getId(), page.getChapterId(), page.getId(), taskId,
                    GenerationRecord.KIND_LAYOUT, ctx.configService.getString("ai_image_model"),
                    result.prompt(), result.refUrls(), null, result.url(),
                    GenerationRecord.STATUS_SUCCESS, null);
            return "{\"pageId\":" + page.getId() + ",\"layoutImageUrl\":\"" + result.url() + "\"}";
        });
        if (!commit.committed()) {
            throw new StaleCommitRejectedException(execution.item().getId());
        }
        return commit.resultRef();
    }

    /** 同步 LAYOUT Items:按目标页建缺的 Item、失败重排。
     *  Phase 8.2 §4.5:孤儿清理仅在项目级全量跑时执行,scoped 任务不得删除其他话的 Item。 */
    private void syncItems(Project project, Long chapterId, boolean projectWide) {
        List<PageEntity> pages = ctx.pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                .eq(PageEntity::getProjectId, project.getId())
                .eq(chapterId != null, PageEntity::getChapterId, chapterId)
                .orderByAsc(PageEntity::getChapterId)
                .orderByAsc(PageEntity::getPageNo));
        if (projectWide) {
            stageService.removeOrphanItems(project.getId(), PipelineStageService.STAGE_LAYOUT, BUSINESS_TYPE_PAGE,
                    pages.stream().map(PageEntity::getId).toList());
        }
        List<Long> needLayout = pages.stream()
                .filter(LayoutTaskHandler::staleOrMissingLayout)
                .map(PageEntity::getId)
                .toList();
        stageService.createItems(project.getId(), PipelineStageService.STAGE_LAYOUT, BUSINESS_TYPE_PAGE, needLayout);
        List<Long> targetPageIds = pages.stream().map(PageEntity::getId).toList();
        stageService.resetSuccessfulItemsByBusiness(project.getId(), PipelineStageService.STAGE_LAYOUT,
                BUSINESS_TYPE_PAGE, needLayout);
        stageService.resetFailedItemsByBusiness(project.getId(), PipelineStageService.STAGE_LAYOUT,
                BUSINESS_TYPE_PAGE, targetPageIds);
    }

    private static boolean staleOrMissingLayout(PageEntity page) {
        if (page.getLayoutImageUrl() == null || page.getLayoutImageUrl().isBlank()) {
            return true;
        }
        int scriptVersion = page.getScriptVersion() == null ? 1 : page.getScriptVersion();
        return page.getLayoutScriptVersion() == null || page.getLayoutScriptVersion() < scriptVersion;
    }

    private Long parseId(String payload, String field) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
            return node.has(field) && node.get(field).canConvertToLong() ? node.get(field).asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
