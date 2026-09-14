package com.aimanga.v2.pipeline;

import com.aimanga.v2.ai.AiService;
import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.Asset;
import com.aimanga.v2.model.PipelineStageItem;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.task.TaskHandler;
import com.aimanga.v2.task.TaskRuntime;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SHEET 角色设定表任务(Phase 5.9 并发生图引擎版):
 * - 角色 = Stage Item(businessType=ASSET),由 ImageStageRunner 按并发上限并发生成;
 * - 单角色失败自动重试(max_retry),不影响其他角色;全部 Item 终态后按整体统计决定阶段成败;
 * - 幂等:已有设定表的角色跳过;暂停时不再领取新 Item,在跑的等待返回并保存;
 * - payload {"assetId": x} → 只重置/生成该角色的 Item(手动重生成);
 * - 全部完成且 project.status 仍为"准备中"时推进为"待出图"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SheetTaskHandler implements TaskHandler {

    public static final String TYPE = "SHEET";
    private static final String BUSINESS_TYPE_ASSET = "ASSET";

    private final PipelineContext ctx;
    private final AiService aiService;
    private final ConcurrentStageRunner stageRunner;
    private final PipelineStageService stageService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(TaskEntity task, TaskRuntime runtime) {
        Project project = ctx.project(task.getProjectId());
        List<Long> assetIds = parseAssetIds(task.getPayload());
        stageService.markRunning(project.getId(), PipelineStageService.STAGE_SHEET);

        if (!assetIds.isEmpty()) {
            // 指定角色(批量勾选/单角色手动重生成):只处理这些。
            // T5.11.1:手动生成必须用 forceReset(SUCCESS 也重置 + force 标记),
            // MaterialGenerationService 已先落过一次,这里幂等兜底(兼容旧路径/直接重试)。
            syncSelectedAssets(project, assetIds);
        } else {
            syncAllAssets(project);
        }
        // T5.11.4:不再在 Handler 里无条件回收 RUNNING——孤儿回收移入 Runner,
        // 且仅在持有 project+stage 唯一执行锁时执行,不会误回收其他存活 Runner 的在跑 Item。

        PipelineStageService.StageItemStats before = stageService.getItemStats(project.getId(), PipelineStageService.STAGE_SHEET);
        if (before.total() == 0 || before.pending() == 0) {
            // 没有角色,或全部已完成(重试续作场景)→ 直接完成
            stageService.markSuccess(project.getId(), PipelineStageService.STAGE_SHEET);
            advanceProject(project);
            return;
        }
        // 任务进度 = 本次要处理的数量(pending 统计含孤儿 RUNNING,Runner 回收后会照常处理)
        runtime.begin((int) before.pending());

        stageRunner.run(project.getId(), PipelineStageService.STAGE_SHEET, runtime,
                item -> generateSheet(project, item), stageRunner.imageEngine());

        // 暂停:阶段保持 PAUSED(pauseProject 已置),由恢复/继续重新入队
        if (stageService.isStagePaused(project.getId(), PipelineStageService.STAGE_SHEET)) {
            log.info("[sheet] 作品 {} SHEET 暂停中,等待继续", project.getId());
            return;
        }

        // 终态判定(以全量 Item 统计为准:单个失败已在 Runner 内重试过,重跑任务可续作)
        PipelineStageService.StageItemStats stats = stageService.getItemStats(project.getId(), PipelineStageService.STAGE_SHEET);
        if (stats.failed() == 0) {
            stageService.markSuccess(project.getId(), PipelineStageService.STAGE_SHEET);
            advanceProject(project);
            log.info("[sheet] 作品 {} SHEET 完成: {}/{} 成功", project.getId(), stats.success(), stats.total());
        } else {
            stageService.markFailed(project.getId(), PipelineStageService.STAGE_SHEET,
                    stats.failed() + "/" + stats.total() + " 个角色设定表生成失败,重跑 SHEET 任务可续作");
        }
    }

    /** 单个 Item 处理器:角色设定表生成(§9 幂等:先存 OSS → 更新资产 URL → 才标 Item 成功) */
    private String generateSheet(Project project, PipelineStageItem item) {
        Asset asset = ctx.assetMapper.selectById(item.getBusinessId());
        if (asset == null) {
            throw new BusinessException(404, "资产不存在: " + item.getBusinessId());
        }
        // 幂等(全量跑):上次进程在"保存 URL 之后、标成功之前"崩溃 → 已有图直接补标成功,不重复生图。
        // 用户手动重生成(forceReset 写入的 force 标记)不受此限制,必须重画。
        boolean forceRegen = PipelineStageService.isForceRequested(item);
        if (!forceRegen && asset.getSheetImageUrl() != null && !asset.getSheetImageUrl().isBlank()) {
            return resultRef(asset.getId(), asset.getSheetImageUrl());
        }
        Asset mark = new Asset();
        mark.setId(asset.getId());
        mark.setGenStatus(Asset.GEN_RUNNING);
        mark.setUpdateTime(LocalDateTime.now());
        ctx.assetMapper.updateById(mark);
        try {
            String style = ctx.stylePromptOf(project);
            String prompt = "为角色「" + asset.getName() + "」创建参考表。综合设定:"
                    + (asset.getDescription() == null ? "" : asset.getDescription())
                    + "。风格:" + (style.isBlank() ? "干净漫画" : style)
                    + "。布局:两行共六姿势——上行三头像(侧/正/笑),下行三全身(正/侧/背)。只输出图像,不要文字。";
            List<String> refs = asset.getReferenceUrl() == null || asset.getReferenceUrl().isBlank()
                    ? List.of() : List.of(asset.getReferenceUrl());
            String url = aiService.generateImage("image", prompt, refs, "3:4", project.getUserId());
            Asset patch = new Asset();
            patch.setId(asset.getId());
            patch.setSheetImageUrl(url);
            patch.setGenStatus(Asset.GEN_IDLE);
            patch.setUpdateTime(LocalDateTime.now());
            ctx.assetMapper.updateById(patch);
            return resultRef(asset.getId(), url);
        } catch (Exception e) {
            Asset patch = new Asset();
            patch.setId(asset.getId());
            patch.setGenStatus(Asset.GEN_FAILED);
            patch.setUpdateTime(LocalDateTime.now());
            ctx.assetMapper.updateById(patch);
            throw e instanceof RuntimeException re ? re : new BusinessException(500, e.getMessage());
        }
    }

    /** 全量同步:清理孤儿 Item → 为尚无设定表的角色建 Item → 重跑时把失败 Item 重新排队 */
    private void syncAllAssets(Project project) {
        List<Asset> characters = ctx.assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, project.getId())
                .eq(Asset::getAssetType, Asset.TYPE_CHARACTER));
        stageService.removeOrphanItems(project.getId(), PipelineStageService.STAGE_SHEET, BUSINESS_TYPE_ASSET,
                characters.stream().map(Asset::getId).collect(Collectors.toList()));
        List<Long> needGen = characters.stream()
                .filter(a -> a.getSheetImageUrl() == null || a.getSheetImageUrl().isBlank())
                .map(Asset::getId)
                .toList();
        stageService.createItems(project.getId(), PipelineStageService.STAGE_SHEET, BUSINESS_TYPE_ASSET, needGen);
        int requeued = stageService.resetFailedItems(project.getId(), PipelineStageService.STAGE_SHEET);
        if (requeued > 0) {
            log.info("[sheet] 作品 {} 重新排队 {} 个失败角色 Item", project.getId(), requeued);
        }
    }

    /** 手动重生成指定角色(批量):强制重置这些角色的 Item(T5.11.1,SUCCESS 也重置),不影响其他单元 */
    private void syncSelectedAssets(Project project, List<Long> assetIds) {
        for (Long assetId : assetIds) {
            Asset asset = ctx.assetMapper.selectById(assetId);
            if (asset == null || !asset.getProjectId().equals(project.getId())) {
                throw new BusinessException(404, "资产不存在: " + assetId);
            }
            stageService.createItems(project.getId(), PipelineStageService.STAGE_SHEET, BUSINESS_TYPE_ASSET, List.of(assetId));
            stageService.forceResetItemsByBusiness(project.getId(), PipelineStageService.STAGE_SHEET, BUSINESS_TYPE_ASSET, List.of(assetId));
        }
    }

    private void advanceProject(Project project) {
        Project current = ctx.project(project.getId());
        if (current.getStatus() != null && current.getStatus() == Project.STATUS_PREPARING) {
            Project patch = new Project();
            patch.setId(project.getId());
            patch.setStatus(Project.STATUS_READY);
            patch.setUpdateTime(LocalDateTime.now());
            ctx.projectMapper.updateById(patch);
            log.info("[sheet] 作品 {} 准备完成,状态推进为「待出图」", project.getId());
        }
    }

    private String resultRef(Long assetId, String url) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    Map.of("assetId", assetId, "sheetImageUrl", url));
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 解析目标角色:{"assetIds":[1,2,3]}(批量勾选)或 {"assetId":1}(旧单角色),无 payload = 全量缺图角色。
     */
    private List<Long> parseAssetIds(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
            List<Long> ids = new java.util.ArrayList<>();
            if (node.has("assetIds") && node.get("assetIds").isArray()) {
                node.get("assetIds").forEach(n -> {
                    if (n.canConvertToLong()) {
                        ids.add(n.asLong());
                    }
                });
            } else if (node.has("assetId") && node.get("assetId").canConvertToLong()) {
                ids.add(node.get("assetId").asLong());
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }
}
