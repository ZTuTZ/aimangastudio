package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.PipelineStageItem;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.task.TaskHandler;
import com.aimanga.v2.task.TaskRuntime;
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

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(TaskEntity task, TaskRuntime runtime) {
        Project project = ctx.project(task.getProjectId());
        JsonNode payload = parse(task.getPayload());
        boolean scopeChapter = payload.path("scope").asText("PROJECT").equals("CHAPTER");
        Long chapterId = payload.has("chapterId") && payload.get("chapterId").canConvertToLong()
                ? payload.get("chapterId").asLong() : null;
        String colorMode = payload.path("colorMode").asText(null);
        boolean skipGenerated = payload.path("skipGenerated").asBoolean(true);
        boolean forceLayout = payload.path("forceLayout").asBoolean(false);
        boolean forceImage = payload.path("forceImage").asBoolean(false);

        List<PageEntity> pages = targetPages(project.getId(), chapterId);

        // ===== Gate(T6.3.6) =====
        if (pages.isEmpty()) {
            throw new BusinessException(400, "目标范围没有页面,请先完成脚本生成");
        }
        List<Chapter> chapters = ctx.chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getProjectId, project.getId())
                .eq(chapterId != null, Chapter::getId, chapterId));
        long notReady = chapters.stream()
                .filter(c -> c.getStatus() == null || c.getStatus() < Chapter.STATUS_SCRIPT_READY)
                .count();
        if (notReady > 0) {
            throw new BusinessException(400, notReady + " 话脚本尚未生成完成,请先完成脚本");
        }
        var preflight = generationPreflightService.preflight(project.getId(), chapterId);
        if (!preflight.ready()) {
            String names = preflight.missingRequiredAssets().stream()
                    .map(a -> "「" + a.name() + "」").toList().toString();
            throw new BusinessException(400, "缺少必需角色素材: " + names + ",请先在资产库勾选生成(系统不会自动补素材)");
        }

        // ===== 状态:项目/话 → 出图中 =====
        markProjectStatus(project.getId(), Project.STATUS_GENERATING);
        chapters.forEach(c -> markChapterStatus(c.getId(), Chapter.STATUS_GENERATING));

        int totalSteps = pages.size() + (int) pages.stream()
                .filter(p -> forceImage || !skipGenerated || staleOrMissing(p))
                .count();
        runtime.begin(Math.max(1, totalSteps));

        // ===== 阶段一:LAYOUT(T6.3.4) =====
        stageService.markRunning(project.getId(), PipelineStageService.STAGE_LAYOUT);
        syncLayoutItems(project.getId(), pages, forceLayout);
        stageRunner.run(project.getId(), PipelineStageService.STAGE_LAYOUT, runtime,
                item -> {
                    PageEntity page = pageOf(item.getBusinessId());
                    return "{\"pageId\":" + page.getId() + ",\"layout\":\""
                            + layoutGenerationService.processPage(project, page, PipelineStageService.isForceRequested(item))
                            + "\"}";
                }, stageRunner.imageEngine());
        if (stageService.isStagePaused(project.getId(), PipelineStageService.STAGE_LAYOUT)) {
            log.info("[batch] 作品 {} LAYOUT 暂停,等待继续", project.getId());
            return;
        }

        // ===== 阶段二:IMAGE(成品页并发) =====
        stageService.markRunning(project.getId(), PipelineStageService.STAGE_IMAGE);
        List<PageEntity> pagesAfterLayout = targetPages(project.getId(), chapterId);
        syncImageItems(project.getId(), pagesAfterLayout, skipGenerated, forceImage);
        stageRunner.run(project.getId(), PipelineStageService.STAGE_IMAGE, runtime,
                item -> {
                    PageEntity page = pageOf(item.getBusinessId());
                    return "{\"pageId\":" + page.getId() + ",\"image\":\""
                            + pageGenerationService.processPage(project, page, colorMode,
                                    PipelineStageService.isForceRequested(item))
                            + "\"}";
                }, stageRunner.imageEngine());
        if (stageService.isStagePaused(project.getId(), PipelineStageService.STAGE_IMAGE)) {
            log.info("[batch] 作品 {} IMAGE 暂停,等待继续", project.getId());
            return;
        }

        // ===== 汇总(T6.3.7) =====
        summarize(project, pagesAfterLayout, chapterId);
    }

    /** 汇总:阶段成败、话状态、项目状态、默认封面(T6.3.9) */
    private void summarize(Project project, List<PageEntity> pages, Long chapterId) {
        PipelineStageService.StageItemStats imageStats =
                stageService.getItemStats(project.getId(), PipelineStageService.STAGE_IMAGE);
        if (imageStats.failed() == 0) {
            stageService.markSuccess(project.getId(), PipelineStageService.STAGE_IMAGE);
        } else {
            stageService.markFailed(project.getId(), PipelineStageService.STAGE_IMAGE,
                    imageStats.failed() + "/" + imageStats.total() + " 页成品生成失败,重跑可续作");
        }
        // LAYOUT 阶段终态(若此前未标记)
        PipelineStageService.StageItemStats layoutStats =
                stageService.getItemStats(project.getId(), PipelineStageService.STAGE_LAYOUT);
        if (layoutStats.failed() == 0) {
            stageService.markSuccess(project.getId(), PipelineStageService.STAGE_LAYOUT);
        }

        // 话状态
        List<Long> chapterIds = pages.stream().map(PageEntity::getChapterId).distinct().toList();
        List<PageEntity> latest = ctx.pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                .eq(PageEntity::getProjectId, project.getId())
                .in(PageEntity::getChapterId, chapterIds));
        for (Long cid : chapterIds) {
            List<PageEntity> chapterPages = latest.stream().filter(p -> p.getChapterId().equals(cid)).toList();
            long failed = chapterPages.stream().filter(p -> p.getGenerateStatus() != null
                    && p.getGenerateStatus() == PageEntity.GEN_FAILED).count();
            if (failed == 0) {
                markChapterStatus(cid, Chapter.STATUS_COMPLETE);
            } else {
                markChapterStatus(cid, Chapter.STATUS_PARTIAL_FAILED);
            }
        }

        // 项目状态
        long failedPages = latest.stream().filter(p -> p.getGenerateStatus() != null
                && p.getGenerateStatus() == PageEntity.GEN_FAILED).count();
        if (failedPages == 0) {
            markProjectStatus(project.getId(), Project.STATUS_DONE);
        } else {
            markProjectStatus(project.getId(), Project.STATUS_PARTIAL);
        }

        // 默认封面:整部生成完成且未设人工封面时,取第一张成品页
        if (failedPages == 0 && chapterId == null
                && (project.getCoverUrl() == null || project.getCoverUrl().isBlank())) {
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
        log.info("[batch] 作品 {} BATCH 汇总: 成品失败 {} 页", project.getId(), failedPages);
    }

    /** LAYOUT Items:全部目标页建 Item(幂等跳过已有图);forceLayout 时全部强制重画 */
    private void syncLayoutItems(Long projectId, List<PageEntity> pages, boolean forceLayout) {
        stageService.removeOrphanItems(projectId, PipelineStageService.STAGE_LAYOUT, BUSINESS_TYPE_PAGE,
                pages.stream().map(PageEntity::getId).toList());
        stageService.createItems(projectId, PipelineStageService.STAGE_LAYOUT, BUSINESS_TYPE_PAGE,
                pages.stream().map(PageEntity::getId).toList());
        if (forceLayout) {
            stageService.forceResetItemsByBusiness(projectId, PipelineStageService.STAGE_LAYOUT,
                    BUSINESS_TYPE_PAGE, pages.stream().map(PageEntity::getId).toList());
        } else {
            stageService.resetFailedItems(projectId, PipelineStageService.STAGE_LAYOUT);
        }
    }

    /** IMAGE Items:skipGenerated=true 时只处理尚无成品图的页;forceImage 时全部强制重画 */
    private void syncImageItems(Long projectId, List<PageEntity> pages, boolean skipGenerated, boolean forceImage) {
        List<Long> needGen = pages.stream()
                .filter(p -> forceImage || !skipGenerated || staleOrMissing(p))
                .map(PageEntity::getId)
                .toList();
        stageService.removeOrphanItems(projectId, PipelineStageService.STAGE_IMAGE, BUSINESS_TYPE_PAGE,
                pages.stream().map(PageEntity::getId).toList());
        stageService.createItems(projectId, PipelineStageService.STAGE_IMAGE, BUSINESS_TYPE_PAGE, needGen);
        if (forceImage) {
            stageService.forceResetItemsByBusiness(projectId, PipelineStageService.STAGE_IMAGE,
                    BUSINESS_TYPE_PAGE, needGen);
        } else {
            stageService.resetFailedItems(projectId, PipelineStageService.STAGE_IMAGE);
        }
    }

    private List<PageEntity> targetPages(Long projectId, Long chapterId) {
        return ctx.pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                .eq(PageEntity::getProjectId, projectId)
                .eq(chapterId != null, PageEntity::getChapterId, chapterId)
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

    private JsonNode parse(String payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    payload == null || payload.isBlank() ? "{}" : payload);
        } catch (Exception e) {
            throw new BusinessException(400, "BATCH payload 解析失败");
        }
    }
}
