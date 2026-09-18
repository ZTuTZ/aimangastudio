package com.aimanga.v2.pipeline;

import com.aimanga.v2.ai.AiService;
import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.Asset;
import com.aimanga.v2.model.PipelineStage;
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

/**
 * ASSET_REF 场景/道具/服装参考图任务(素材工作台):
 * - 用户在资产库勾选后触发(payload {"assetIds":[...]});无 payload = 全量补齐缺参考图的三类资产;
 * - 每个资产一张概念参考图,画幅按作品配置:场景 sceneRatio(默认16:9)/道具 propRatio(默认1:1)/服装 costumeRatio(默认3:4);
 * - 结果写入 asset.referenceUrl(Phase 6 页面生成时作为一致性参考图);
 * - 走 ImageStageRunner 并发引擎:原子领取/失败重试/暂停恢复;幂等:已有参考图且非强制时跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetRefTaskHandler implements TaskHandler {

    public static final String TYPE = "ASSET_REF";
    private static final String BUSINESS_TYPE_ASSET = "ASSET";

    private final PipelineContext ctx;
    private final AiService aiService;
    private final ConcurrentStageRunner stageRunner;
    private final PipelineStageService stageService;
    private final StageItemCommitService commitService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(TaskEntity task, TaskRuntime runtime) {
        Project project = ctx.project(task.getProjectId());
        List<Long> assetIds = parseAssetIds(task.getPayload());
        stageService.markRunning(project.getId(), PipelineStageService.STAGE_REFERENCE);

        if (!assetIds.isEmpty()) {
            syncSelected(project, assetIds);
        } else {
            syncAllMissing(project);
        }
        // T5.11.4:孤儿 RUNNING 回收移入 Runner,仅在持有 project+stage 唯一执行锁时执行。

        PipelineStageService.StageItemStats before = stageService.getItemStats(project.getId(), PipelineStageService.STAGE_REFERENCE);
        if (before.total() == 0 || before.pending() == 0) {
            stageService.markSuccess(project.getId(), PipelineStageService.STAGE_REFERENCE);
            return;
        }
        runtime.begin((int) before.pending());

        // Phase 8.2:手动勾选 → 只执行这些资产的 Item;全量跑 → 项目级
        StageRunScope scope = assetIds.isEmpty() ? StageRunScope.all() : StageRunScope.assets(assetIds);
        stageRunner.run(project.getId(), PipelineStageService.STAGE_REFERENCE, scope, runtime,
                execution -> generateRef(project, execution), stageRunner.imageEngine());

        if (stageService.isStagePaused(project.getId(), PipelineStageService.STAGE_REFERENCE)) {
            log.info("[asset-ref] 作品 {} 素材参考图暂停中,等待继续", project.getId());
            return;
        }

        int stageStatus = stageService.refreshStageTerminalState(project.getId(), PipelineStageService.STAGE_REFERENCE);
        PipelineStageService.StageItemStats stats = stageService.getItemStats(project.getId(), PipelineStageService.STAGE_REFERENCE);
        if (stageStatus == PipelineStage.STATUS_SUCCESS) {
            log.info("[asset-ref] 作品 {} 素材参考图完成: {}/{}", project.getId(), stats.success(), stats.total());
        } else if (stageStatus == PipelineStage.STATUS_FAILED) {
            log.warn("[asset-ref] 作品 {} 素材参考图存在失败: {}/{}", project.getId(), stats.failed(), stats.total());
        }
    }

    /** 单资产处理器:参考图生成(§9 幂等 + Phase 8.1 fenced commit) */
    private String generateRef(Project project, StageItemExecution execution) {
        PipelineStageItem item = execution.item();
        boolean forceRegen = PipelineStageService.isForceRequested(item);
        Asset asset = ctx.assetMapper.selectById(item.getBusinessId());
        if (asset == null) {
            throw new BusinessException(404, "资产不存在: " + item.getBusinessId());
        }
        // 幂等(全量跑):已有参考图直接补标成功;用户手动重生成(force 标记,T5.11.1)强制重画
        if (!forceRegen && asset.getReferenceUrl() != null && !asset.getReferenceUrl().isBlank()) {
            return resultRef(asset.getId(), asset.getReferenceUrl());
        }
        Asset mark = new Asset();
        mark.setId(asset.getId());
        mark.setGenStatus(Asset.GEN_RUNNING);
        mark.setUpdateTime(LocalDateTime.now());
        ctx.assetMapper.updateById(mark);
        try {
            String ratio = ratioOf(project, asset.getAssetType());
            String subject = subjectOf(asset.getAssetType());
            String style = ctx.stylePromptOf(project);
            String prompt = "为" + subject + "「" + asset.getName() + "」生成一张概念参考图。设定:"
                    + (asset.getDescription() == null ? "" : asset.getDescription())
                    + "。风格:" + (style.isBlank() ? "干净漫画" : style)
                    + "。画面只包含" + subject + "本身,不要文字、不要人物。";
            List<String> refs = sheetRefOf(asset);
            String url = aiService.generateImage("image", prompt, refs, ratio, project.getUserId());
            // Phase 8.1 fenced commit:资产写入 + Item SUCCESS 同事务
            var commit = commitService.commitFenced(execution, () -> {
                Asset patch = new Asset();
                patch.setId(asset.getId());
                patch.setReferenceUrl(url);
                patch.setGenStatus(Asset.GEN_IDLE);
                patch.setUpdateTime(LocalDateTime.now());
                ctx.assetMapper.updateById(patch);
                return resultRef(asset.getId(), url);
            });
            if (!commit.committed()) {
                throw new StaleCommitRejectedException(item.getId());
            }
            return commit.resultRef();
        } catch (Exception e) {
            Asset patch = new Asset();
            patch.setId(asset.getId());
            patch.setGenStatus(Asset.GEN_FAILED);
            patch.setUpdateTime(LocalDateTime.now());
            ctx.assetMapper.updateById(patch);
            throw e instanceof RuntimeException re ? re : new BusinessException(500, e.getMessage());
        }
    }

    /** 角色已有设定表时作为参考图传入,提升场景/道具/服装与角色风格一致性 */
    private List<String> sheetRefOf(Asset asset) {
        return asset.getSheetImageUrl() == null || asset.getSheetImageUrl().isBlank()
                ? List.of() : List.of(asset.getSheetImageUrl());
    }

    /** 按资产类型取画幅配置 */
    String ratioOf(Project project, Integer assetType) {
        if (assetType == null) {
            return "1:1";
        }
        return switch (assetType) {
            case Asset.TYPE_SCENE -> PipelineContext.safe(project.getSceneRatio()).isBlank() ? "16:9" : project.getSceneRatio();
            case Asset.TYPE_OUTFIT -> PipelineContext.safe(project.getCostumeRatio()).isBlank() ? "3:4" : project.getCostumeRatio();
            default -> PipelineContext.safe(project.getPropRatio()).isBlank() ? "1:1" : project.getPropRatio();
        };
    }

    /** 资产类型的提示词主体名 */
    static String subjectOf(Integer assetType) {
        if (assetType == null) {
            return "道具";
        }
        return switch (assetType) {
            case Asset.TYPE_SCENE -> "场景";
            case Asset.TYPE_OUTFIT -> "服装";
            default -> "道具";
        };
    }

    /** 指定资产(批量勾选):校验归属并确保 Item 存在且已排队,强制重画 */
    private void syncSelected(Project project, List<Long> assetIds) {
        for (Long assetId : assetIds) {
            Asset asset = ctx.assetMapper.selectById(assetId);
            if (asset == null || !asset.getProjectId().equals(project.getId())) {
                throw new BusinessException(404, "资产不存在: " + assetId);
            }
            if (asset.getAssetType() == null || asset.getAssetType() == Asset.TYPE_CHARACTER) {
                throw new BusinessException(400, "参考图生成仅支持场景/道具/服装: " + asset.getName());
            }
            stageService.createItems(project.getId(), PipelineStageService.STAGE_REFERENCE, BUSINESS_TYPE_ASSET, List.of(assetId));
            stageService.forceResetItemsByBusiness(project.getId(), PipelineStageService.STAGE_REFERENCE, BUSINESS_TYPE_ASSET, List.of(assetId));
        }
    }

    /** 全量补齐:为尚无参考图的场景/道具/服装建 Item,并重跑失败单元 */
    private void syncAllMissing(Project project) {
        List<Asset> targets = ctx.assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, project.getId())
                .in(Asset::getAssetType, Asset.TYPE_SCENE, Asset.TYPE_PROP, Asset.TYPE_OUTFIT));
        stageService.removeOrphanItems(project.getId(), PipelineStageService.STAGE_REFERENCE, BUSINESS_TYPE_ASSET,
                targets.stream().map(Asset::getId).toList());
        List<Long> needGen = targets.stream()
                .filter(a -> a.getReferenceUrl() == null || a.getReferenceUrl().isBlank())
                .map(Asset::getId)
                .toList();
        stageService.createItems(project.getId(), PipelineStageService.STAGE_REFERENCE, BUSINESS_TYPE_ASSET, needGen);
        stageService.resetFailedItems(project.getId(), PipelineStageService.STAGE_REFERENCE);
    }

    private String resultRef(Long assetId, String url) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    Map.of("assetId", assetId, "referenceUrl", url));
        } catch (Exception e) {
            return "{}";
        }
    }

    /** {"assetIds":[...]}(批量勾选);无 payload = 全量缺图 */
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
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }
}
