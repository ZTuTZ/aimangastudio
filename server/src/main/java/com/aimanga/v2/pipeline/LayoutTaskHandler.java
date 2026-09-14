package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.PipelineStageItem;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.task.TaskHandler;
import com.aimanga.v2.task.TaskRuntime;
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

        syncItems(project, chapterId);
        if (pageId != null) {
            // 手动单页重布局:强制重置该页 Item(force 标记 → 跳过幂等)
            stageService.forceResetItemsByBusiness(project.getId(), PipelineStageService.STAGE_LAYOUT,
                    BUSINESS_TYPE_PAGE, List.of(pageId));
        }

        PipelineStageService.StageItemStats before =
                stageService.getItemStats(project.getId(), PipelineStageService.STAGE_LAYOUT);
        if (before.total() == 0 || before.pending() == 0) {
            stageService.markSuccess(project.getId(), PipelineStageService.STAGE_LAYOUT);
            return;
        }
        runtime.begin((int) before.pending());

        stageRunner.run(project.getId(), PipelineStageService.STAGE_LAYOUT, runtime,
                item -> processOnePage(project, item), stageRunner.imageEngine());

        if (stageService.isStagePaused(project.getId(), PipelineStageService.STAGE_LAYOUT)) {
            log.info("[layout] 作品 {} LAYOUT 暂停中,等待继续", project.getId());
            return;
        }

        PipelineStageService.StageItemStats stats =
                stageService.getItemStats(project.getId(), PipelineStageService.STAGE_LAYOUT);
        if (stats.failed() == 0) {
            stageService.markSuccess(project.getId(), PipelineStageService.STAGE_LAYOUT);
            log.info("[layout] 作品 {} LAYOUT 完成: {}/{}", project.getId(), stats.success(), stats.total());
        } else {
            stageService.markFailed(project.getId(), PipelineStageService.STAGE_LAYOUT,
                    stats.failed() + "/" + stats.total() + " 页布局图生成失败,重跑任务可续作");
        }
    }

    /** 单页处理(Item → 布局图);force 标记由 forceResetItemsByBusiness 写入 */
    private String processOnePage(Project project, PipelineStageItem item) {
        PageEntity page = layoutGenerationService.page(item.getBusinessId());
        boolean force = PipelineStageService.isForceRequested(item);
        String url = layoutGenerationService.processPage(project, page, force);
        return "{\"pageId\":" + page.getId() + ",\"layoutImageUrl\":\"" + url + "\"}";
    }

    /** 同步 LAYOUT Items:按目标页建缺的 Item、清理孤儿 Item、失败重排 */
    private void syncItems(Project project, Long chapterId) {
        List<PageEntity> pages = ctx.pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                .eq(PageEntity::getProjectId, project.getId())
                .eq(chapterId != null, PageEntity::getChapterId, chapterId)
                .orderByAsc(PageEntity::getChapterId)
                .orderByAsc(PageEntity::getPageNo));
        stageService.removeOrphanItems(project.getId(), PipelineStageService.STAGE_LAYOUT, BUSINESS_TYPE_PAGE,
                pages.stream().map(PageEntity::getId).toList());
        List<Long> needLayout = pages.stream()
                .filter(p -> p.getLayoutImageUrl() == null || p.getLayoutImageUrl().isBlank())
                .map(PageEntity::getId)
                .toList();
        stageService.createItems(project.getId(), PipelineStageService.STAGE_LAYOUT, BUSINESS_TYPE_PAGE, needLayout);
        stageService.resetFailedItems(project.getId(), PipelineStageService.STAGE_LAYOUT);
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
