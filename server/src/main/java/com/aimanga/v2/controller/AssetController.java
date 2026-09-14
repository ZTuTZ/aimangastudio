package com.aimanga.v2.controller;

import com.aimanga.v2.common.Result;
import com.aimanga.v2.dto.AssetVO;
import com.aimanga.v2.dto.SaveAssetRequest;
import com.aimanga.v2.model.Asset;
import com.aimanga.v2.service.AssetService;
import com.aimanga.v2.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;
    private final TaskService taskService;
    private final com.aimanga.v2.pipeline.MaterialGenerationService materialGenerationService;

    @GetMapping("/projects/{projectId}/assets")
    public Result<List<AssetVO>> listByProject(@PathVariable Long projectId) {
        return Result.ok(assetService.listByProject(projectId));
    }

    @PostMapping("/projects/{projectId}/assets")
    public Result<AssetVO> create(@PathVariable Long projectId, @Valid @RequestBody SaveAssetRequest request) {
        return Result.ok(assetService.toVO(assetService.create(projectId, request)));
    }

    @PutMapping("/assets/{id}")
    public Result<AssetVO> update(@PathVariable Long id, @RequestBody SaveAssetRequest request) {
        return Result.ok(assetService.toVO(assetService.update(id, request)));
    }

    @DeleteMapping("/assets/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        assetService.delete(id);
        return Result.ok();
    }

    /** 生成/重新生成该角色的六姿势设定表(创建 SHEET 任务) */
    @PostMapping("/assets/{id}/generate-sheet")
    public Result<com.aimanga.v2.dto.TaskVO> generateSheet(@PathVariable Long id) {
        Asset asset = assetService.requireAccessible(id);
        var payload = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().createObjectNode().put("assetId", id);
        return Result.ok(taskService.create(
                new com.aimanga.v2.dto.CreateTaskRequest(asset.getProjectId(), null, "SHEET", payload)));
    }

    /**
     * 批量生成勾选资产的素材图:角色→六姿势设定表(SHEET 任务),场景/道具/服装→参考图(ASSET_REF 任务)。
     * 逻辑统一在 MaterialGenerationService(T5.11.3):校验归属 → 落 Items → 复用/创建唯一活跃任务。
     */
    @PostMapping("/projects/{projectId}/assets/generate")
    public Result<List<com.aimanga.v2.dto.TaskVO>> generateBatch(@PathVariable Long projectId,
                                                                 @RequestBody BatchAssetRequest request) {
        return Result.ok(materialGenerationService.requestMaterials(projectId, request.assetIds()));
    }

    private static com.fasterxml.jackson.databind.JsonNode batchPayload(List<Long> assetIds) {
        var mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder().build();
        var node = mapper.createObjectNode();
        var arr = node.putArray("assetIds");
        assetIds.forEach(arr::add);
        return node;
    }

    /** 批量生成请求体 */
    public record BatchAssetRequest(java.util.List<Long> assetIds) {
    }
}
