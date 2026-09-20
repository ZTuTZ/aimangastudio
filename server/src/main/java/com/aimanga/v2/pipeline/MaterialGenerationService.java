package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.dto.TaskVO;
import com.aimanga.v2.model.Asset;
import com.aimanga.v2.service.AssetService;
import com.aimanga.v2.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 素材生成服务(Phase 5.11 T5.11.3):SHEET(角色设定表)/ ASSET_REF(场景/道具/服装参考图)统一入口。
 * Controller 不再直接拼 Task。
 *
 * 流程:
 * 1. 校验资产归属与类型;
 * 2. 创建/强制重置 Stage Items(force 标记 → 处理器对已有素材也强制重画,T5.11.1);
 * 3. 复用同 project+type 的唯一活跃任务(Runner 自动领取新增 PENDING Items),
 *    没有活跃任务才创建 —— 连续快速点击不会产生第二个并行 Runner(T5.11.3)。
 *
 * 注意:正处于 RUNNING 的 Item 被强制重置时,本次在跑的请求会照常完成并保存,
 * 用户的重生成意图对"下一轮"生效(可再次点击);不中断在跑请求以避免浪费已花费的 API 调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialGenerationService {

    private static final String BUSINESS_TYPE_ASSET = "ASSET";

    private final AssetService assetService;
    private final PipelineStageService stageService;
    private final TaskService taskService;

    /** 角色设定表批量生成(勾选的角色) */
    public TaskVO requestCharacterSheets(Long projectId, List<Long> assetIds) {
        return request(projectId, assetIds, PipelineStageService.STAGE_SHEET, "SHEET", Asset.TYPE_CHARACTER);
    }

    /** 场景/道具/服装参考图批量生成(勾选的非角色资产) */
    public TaskVO requestAssetReferences(Long projectId, List<Long> assetIds) {
        return request(projectId, assetIds, PipelineStageService.STAGE_REFERENCE, "ASSET_REF", null);
    }

    /**
     * 批量生成请求:混选时按类型自动拆分(角色→SHEET,其余→ASSET_REF),返回创建/复用的任务列表。
     */
    public List<TaskVO> requestMaterials(Long projectId, List<Long> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            throw new BusinessException(400, "请先勾选要生成的资产");
        }
        List<Long> characterIds = new java.util.ArrayList<>();
        List<Long> refIds = new java.util.ArrayList<>();
        for (Long assetId : assetIds) {
            Asset asset = assetService.requireAccessible(assetId);
            if (!asset.getProjectId().equals(projectId)) {
                throw new BusinessException(404, "资产不存在: " + assetId);
            }
            if (asset.getAssetType() != null && asset.getAssetType() == Asset.TYPE_CHARACTER) {
                characterIds.add(assetId);
            } else {
                refIds.add(assetId);
            }
        }
        List<TaskVO> tasks = new java.util.ArrayList<>();
        if (!characterIds.isEmpty()) {
            tasks.add(requestCharacterSheets(projectId, characterIds));
        }
        if (!refIds.isEmpty()) {
            tasks.add(requestAssetReferences(projectId, refIds));
        }
        return tasks;
    }

    private TaskVO request(Long projectId, List<Long> assetIds, String stageType, String taskType, Integer expectedType) {
        if (assetIds == null || assetIds.isEmpty()) {
            throw new BusinessException(400, "请先勾选要生成的资产");
        }
        for (Long assetId : assetIds) {
            Asset asset = assetService.requireAccessible(assetId);
            if (!asset.getProjectId().equals(projectId)) {
                throw new BusinessException(404, "资产不存在: " + assetId);
            }
            boolean typeOk = expectedType != null
                    ? expectedType.equals(asset.getAssetType())
                    : asset.getAssetType() != null && asset.getAssetType() != Asset.TYPE_CHARACTER;
            if (!typeOk) {
                throw new BusinessException(400, "资产「" + asset.getName() + "」类型不符合该生成动作");
            }
        }
        // Task admission persists the fixed target set and performs the one-time reset.
        log.info("[material] 作品 {} {} 请求生成 {} 个素材", projectId, taskType, assetIds.size());
        return taskService.ensureUniqueActiveTask(projectId, null, taskType, payloadOf(assetIds));
    }

    private static String payloadOf(List<Long> assetIds) {
        try {
            var mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder().build();
            var node = mapper.createObjectNode();
            node.put("force", true);
            var arr = node.putArray("assetIds");
            assetIds.forEach(arr::add);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }
}
