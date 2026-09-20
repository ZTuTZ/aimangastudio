package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.GenerationRecord;
import com.aimanga.v2.model.PipelineStageItem;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.task.TaskHandler;
import com.aimanga.v2.task.TaskRuntime;
import com.aimanga.v2.service.TaskPlanningService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BATCH 整本/按话生成任务(Phase 6.3 T6.3.4):
 * 一个用户点击 = 1 个 BATCH Task → Preflight → LAYOUT Stage(N 页 Item 并发) → IMAGE Stage(N 页 Item 并发) → 汇总。
 *
 * Gate(T6.3.6):话脚本就绪 / 有页面 / 预检通过(缺必需角色素材直接拒绝,绝不静默自动补素材)/ 创建层去重。
 * 幂等(T6.3.8):SUCCESS 布局图与成品页永不重画,已成功页面不产生二次 AI 费用;
 * 暂停:两阶段都支持(不再领取新 Item,在跑的保存结果),由项目级 resume 恢复;
 * 页与页零依赖(T6.3.3):并发安全,跨页一致性由素材参考与风格保证。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchTaskHandler implements TaskHandler {

    public static final String TYPE = "BATCH";
    private static final String BUSINESS_TYPE_PAGE = "PAGE";

    private final PipelineContext ctx;
    private final GenerationPreflightService generationPreflightService;
    private final LayoutGenerationService layoutGenerationService;
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
        JsonNode payload = parse(task.getPayload());
        String scope = payload.path("scope").asText("PROJECT");
        Long chapterId = payload.has("chapterId") && payload.get("chapterId").canConvertToLong()
                ? payload.get("chapterId").asLong() : null;
        // 多话批量(Phase 6.4 增强):scope=CHAPTERS 时取 chapterIds 数组
        List<Long> chapterIds = new java.util.ArrayList<>();
        if (payload.has("chapterIds") && payload.get("chapterIds").isArray()) {
            payload.get("chapterIds").forEach(n -> {
                if (n.canConvertToLong()) chapterIds.add(n.asLong());
            });
        }
        boolean scopedScope = "CHAPTER".equals(scope) || "CHAPTERS".equals(scope);
        boolean projectWide = "PROJECT".equals(scope);
        String colorMode = payload.path("colorMode").asText(null);
        boolean skipGenerated = payload.path("skipGenerated").asBoolean(true);
        boolean forceLayout = payload.path("forceLayout").asBoolean(false);
        boolean forceImage = payload.path("forceImage").asBoolean(false);
        boolean planned = taskPlanningService.hasPlan(task);

        List<PageEntity> pages = targetPages(project.getId(), chapterId, chapterIds);

        // ===== Gate(T6.3.6) =====
        if (pages.isEmpty()) {
            throw new BusinessException(400, "目标范围没有页面,请先完成脚本生成");
        }
        boolean scoped = chapterId != null || !chapterIds.isEmpty();
        List<Chapter> chapters = ctx.chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getProjectId, project.getId())
                .eq(chapterId != null, Chapter::getId, chapterId)
                .in(chapterIds != null && !chapterIds.isEmpty(), Chapter::getId, chapterIds));
        long notReady = chapters.stream()
                .filter(c -> c.getStatus() == null || c.getStatus() < Chapter.STATUS_SCRIPT_READY)
                .count();
        if (notReady > 0) {
            throw new BusinessException(400, notReady + " 话脚本尚未生成完成,请先完成脚本");
        }
        var preflight = generationPreflightService.preflight(project.getId(),
                chapterId != null ? java.util.List.of(chapterId) : chapterIds);
        // 素材 Gate 开关(Phase 7.1):page_generation_asset_gate=1(默认)缺必需角色阻止出图;=0 仅警告放行
        boolean gateEnabled = ctx.configService.getInt("page_generation_asset_gate", 1) == 1;
        if (!preflight.ready() && gateEnabled) {
            String names = preflight.missingRequiredAssets().stream()
                    .map(a -> "「" + a.name() + "」").toList().toString();
            throw new BusinessException(400, "缺少必需角色素材: " + names + ",请先在资产库勾选生成(系统不会自动补素材)");
        }

        // ===== 状态:项目/话 → 出图中 =====
        markProjectStatus(project.getId(), Project.STATUS_GENERATING);
        chapters.forEach(c -> markChapterStatus(c.getId(), Chapter.STATUS_GENERATING));

        // page_direct_output=1:直接出成品,跳过布局阶段(配置开关,热生效)
        boolean directOutput = ctx.configService.getInt("page_direct_output", 0) == 1;
        List<Long> layoutTargetIds = planned
                ? taskPlanningService.targetIds(task, PipelineStageService.STAGE_LAYOUT, BUSINESS_TYPE_PAGE)
                : directOutput ? List.of() : pages.stream().map(PageEntity::getId).toList();
        List<Long> imageTargetIds = planned
                ? taskPlanningService.targetIds(task, PipelineStageService.STAGE_IMAGE, BUSINESS_TYPE_PAGE)
                : pages.stream().filter(p -> forceImage || !skipGenerated || staleOrMissing(p))
                        .map(PageEntity::getId).toList();
        int totalSteps = layoutTargetIds.size() + imageTargetIds.size();
        runtime.begin(Math.max(1, totalSteps));

        // ===== 阶段一:LAYOUT(T6.3.4;直接出图模式整段跳过) =====
        if (!layoutTargetIds.isEmpty()) {
            stageService.markRunning(project.getId(), PipelineStageService.STAGE_LAYOUT);
            if (!planned) syncLayoutItems(project.getId(), pages, forceLayout, projectWide);
            stageRunner.run(project.getId(), PipelineStageService.STAGE_LAYOUT,
                    StageRunScope.pages(layoutTargetIds), runtime,
                    execution -> {
                        PipelineStageItem item = execution.item();
                        PageEntity page = pageOf(item.getBusinessId());
                        TaskPlanningService.PlannedPageInput input = taskPlanningService.requireCurrentPageInput(
                                execution.planUnitId(), page, false);
                        LayoutGenerationService.LayoutResult result = layoutGenerationService.processPage(
                                project, page, PipelineStageService.isForceRequested(item));
                        var commit = commitService.commitFenced(execution, () -> {
                            layoutGenerationService.applyLayoutResult(page.getId(), input.scriptVersion(), result);
                            generationRecordService.record(project.getId(), page.getChapterId(), page.getId(), task.getId(),
                                    GenerationRecord.KIND_LAYOUT, ctx.configService.getString("ai_image_model"),
                                    result.prompt(), result.refUrls(), null, result.url(),
                                    GenerationRecord.STATUS_SUCCESS, null);
                            return "{\"pageId\":" + page.getId() + ",\"layout\":\"" + result.url() + "\"}";
                        });
                        if (!commit.committed()) {
                            throw new StaleCommitRejectedException(item.getId());
                        }
                        return commit.resultRef();
                    }, stageRunner.imageEngine());
        }

        // ===== 阶段二:IMAGE(成品页并发) =====
        List<PageEntity> pagesAfterLayout = targetPages(project.getId(), chapterId, chapterIds);
        if (!imageTargetIds.isEmpty()) {
        stageService.markRunning(project.getId(), PipelineStageService.STAGE_IMAGE);
        if (!planned) syncImageItems(project.getId(), pagesAfterLayout, skipGenerated, forceImage, projectWide);
        stageRunner.run(project.getId(), PipelineStageService.STAGE_IMAGE, StageRunScope.pages(imageTargetIds), runtime,
                execution -> {
                    PipelineStageItem item = execution.item();
                    PageEntity page = pageOf(item.getBusinessId());
                    TaskPlanningService.PlannedPageInput input = taskPlanningService.requireCurrentPageInput(
                            execution.planUnitId(), page, true);
                    if (!commitService.runWhileOwned(execution, () -> pageGenerationService.markPageRunning(page.getId()))) {
                        throw new StaleCommitRejectedException(item.getId());
                    }
                    try {
                        PageGenerationService.PageGenResult result = pageGenerationService.processPage(
                                project, page, colorMode, PipelineStageService.isForceRequested(item));
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
        } else {
            stageService.refreshStageTerminalState(project.getId(), PipelineStageService.STAGE_IMAGE);
        }

        // ===== 汇总(T6.3.7 + Phase 8.2:整部重算项目状态,修复 P0-3) =====
        summarize(project, pagesAfterLayout, projectWide, directOutput);
    }

    /** 汇总(Phase 8.2):阶段终态刷新 + 话/项目状态由 ProjectCompletionService 整部重算(修复 P0-3);默认封面(T6.3.9,仅整部) */
    private void summarize(Project project, List<PageEntity> pages, boolean wholeProject, boolean directOutput) {
        if (!directOutput) {
            stageService.refreshStageTerminalState(project.getId(), PipelineStageService.STAGE_LAYOUT);
        }
        stageService.refreshStageTerminalState(project.getId(), PipelineStageService.STAGE_IMAGE);

        List<Long> chapterIds = pages.stream().map(PageEntity::getChapterId).distinct().toList();
        for (Long cid : chapterIds) {
            projectCompletionService.recalculateChapter(cid);
        }
        projectCompletionService.recalculateProject(project.getId());

        Project fresh = ctx.project(project.getId());
        boolean allDone = fresh.getStatus() != null && fresh.getStatus() == Project.STATUS_DONE;
        if (allDone && wholeProject && (project.getCoverUrl() == null || project.getCoverUrl().isBlank())) {
            List<PageEntity> latest = ctx.pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                    .eq(PageEntity::getProjectId, project.getId())
                    .orderByAsc(PageEntity::getChapterId)
                    .orderByAsc(PageEntity::getPageNo));
            latest.stream()
                    .filter(p -> p.getGenerateStatus() != null && p.getGenerateStatus() == PageEntity.GEN_SUCCESS
                            && p.getGeneratedImageUrl() != null && !p.getGeneratedImageUrl().isBlank())
                    .findFirst()
                    .ifPresent(p -> {
                        Project patch = new Project();
                        patch.setId(project.getId());
                        patch.setCoverUrl(p.getGeneratedImageUrl());
                        patch.setUpdateTime(LocalDateTime.now());
                        ctx.projectMapper.updateById(patch);
                        log.info("[batch] 作品 {} 已设默认封面(第一张成品页): {}", project.getId(), p.getGeneratedImageUrl());
                    });
        }
        log.info("[batch] 作品 {} BATCH 汇总完成(整部={}, 状态重算)", project.getId(), wholeProject);
    }

    /** LAYOUT Items:全部目标页建 Item(幂等跳过已有图);forceLayout 时全部强制重画。
     *  Phase 8.2 §4.5:orphan 清理只在项目级全量跑时执行,scoped 任务不得删除其他话的 Item。 */
    private void syncLayoutItems(Long projectId, List<PageEntity> pages, boolean forceLayout, boolean projectWide) {
        if (projectWide) {
            stageService.removeOrphanItems(projectId, PipelineStageService.STAGE_LAYOUT, BUSINESS_TYPE_PAGE,
                    pages.stream().map(PageEntity::getId).toList());
        }
        stageService.createItems(projectId, PipelineStageService.STAGE_LAYOUT, BUSINESS_TYPE_PAGE,
                pages.stream().map(PageEntity::getId).toList());
        if (forceLayout) {
            stageService.forceResetItemsByBusiness(projectId, PipelineStageService.STAGE_LAYOUT,
                    BUSINESS_TYPE_PAGE, pages.stream().map(PageEntity::getId).toList());
        } else {
            stageService.resetSuccessfulItemsByBusiness(projectId, PipelineStageService.STAGE_LAYOUT,
                    BUSINESS_TYPE_PAGE, pages.stream().filter(BatchTaskHandler::staleOrMissingLayout)
                            .map(PageEntity::getId).toList());
            stageService.resetFailedItemsByBusiness(projectId, PipelineStageService.STAGE_LAYOUT,
                    BUSINESS_TYPE_PAGE, pages.stream().map(PageEntity::getId).toList());
        }
    }

    /** IMAGE Items:skipGenerated=true 时只处理尚无成品图的页;forceImage 时全部强制重画 */
    private void syncImageItems(Long projectId, List<PageEntity> pages, boolean skipGenerated, boolean forceImage,
                                boolean projectWide) {
        List<Long> needGen = pages.stream()
                .filter(p -> forceImage || !skipGenerated || staleOrMissing(p))
                .map(PageEntity::getId)
                .toList();
        if (projectWide) {
            stageService.removeOrphanItems(projectId, PipelineStageService.STAGE_IMAGE, BUSINESS_TYPE_PAGE,
                    pages.stream().map(PageEntity::getId).toList());
        }
        stageService.createItems(projectId, PipelineStageService.STAGE_IMAGE, BUSINESS_TYPE_PAGE, needGen);
        if (forceImage) {
            stageService.forceResetItemsByBusiness(projectId, PipelineStageService.STAGE_IMAGE,
                    BUSINESS_TYPE_PAGE, needGen);
        } else {
            stageService.resetSuccessfulItemsByBusiness(projectId, PipelineStageService.STAGE_IMAGE,
                    BUSINESS_TYPE_PAGE, needGen);
            stageService.resetFailedItemsByBusiness(projectId, PipelineStageService.STAGE_IMAGE,
                    BUSINESS_TYPE_PAGE, needGen);
        }
    }

    private List<PageEntity> targetPages(Long projectId, Long chapterId, List<Long> chapterIds) {
        boolean scoped = chapterId != null || (chapterIds != null && !chapterIds.isEmpty());
        return ctx.pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                .eq(PageEntity::getProjectId, projectId)
                .eq(chapterId != null, PageEntity::getChapterId, chapterId)
                .in(chapterIds != null && !chapterIds.isEmpty(), PageEntity::getChapterId, chapterIds)
                .orderByAsc(PageEntity::getChapterId)
                .orderByAsc(PageEntity::getPageNo));
    }

    private PageEntity pageOf(Long pageId) {
        PageEntity page = ctx.pageMapper.selectById(pageId);
        if (page == null) {
            throw new BusinessException(404, "页面不存在: " + pageId);
        }
        return page;
    }

    private void markProjectStatus(Long projectId, int status) {
        Project patch = new Project();
        patch.setId(projectId);
        patch.setStatus(status);
        patch.setUpdateTime(LocalDateTime.now());
        ctx.projectMapper.updateById(patch);
    }

    private void markChapterStatus(Long chapterId, int status) {
        Chapter patch = new Chapter();
        patch.setId(chapterId);
        patch.setStatus(status);
        patch.setUpdateTime(LocalDateTime.now());
        ctx.chapterMapper.updateById(patch);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** T6.5.4:无成品图,或脚本版本已更新(成品过期)都视为需要生成 */
    private static boolean staleOrMissing(PageEntity p) {
        if (blank(p.getGeneratedImageUrl())) {
            return true;
        }
        int scriptVersion = p.getScriptVersion() == null ? 1 : p.getScriptVersion();
        Integer imageVersion = p.getImageScriptVersion();
        return imageVersion == null || imageVersion < scriptVersion;
    }

    private static boolean staleOrMissingLayout(PageEntity p) {
        if (blank(p.getLayoutImageUrl())) {
            return true;
        }
        int scriptVersion = p.getScriptVersion() == null ? 1 : p.getScriptVersion();
        Integer layoutVersion = p.getLayoutScriptVersion();
        return layoutVersion == null || layoutVersion < scriptVersion;
    }

    private JsonNode parse(String payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    payload == null || payload.isBlank() ? "{}" : payload);
        } catch (Exception e) {
            throw new BusinessException(400, "BATCH payload 解析失败");
        }
    }
}
