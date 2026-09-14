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
     * 混选时按类型拆成多个任务,返回创建的任务列表。
     */
    @PostMapping("/projects/{projectId}/assets/generate")
    public Result<List<com.aimanga.v2.dto.TaskVO>> generateBatch(@PathVariable Long projectId,
                                                                 @RequestBody BatchAssetRequest request) {
        if (request.assetIds() == null || request.assetIds().isEmpty()) {
            throw new com.aimanga.v2.common.BusinessException(400, "请先勾选要生成的资产");
        }
        List<Long> characterIds = new java.util.ArrayList<>();
        List<Long> refIds = new java.util.ArrayList<>();
        for (Long assetId : request.assetIds()) {
            Asset asset = assetService.requireAccessible(assetId);
            if (!asset.getProjectId().equals(projectId)) {
                throw new com.aimanga.v2.common.BusinessException(404, "资产不存在: " + assetId);
            }
            if (asset.getAssetType() != null && asset.getAssetType() == Asset.TYPE_CHARACTER) {
                characterIds.add(assetId);
            } else {
                refIds.add(assetId);
            }
        }
        if (characterIds.isEmpty() && refIds.isEmpty()) {
            throw new com.aimanga.v2.common.BusinessException(400, "请先勾选要生成的资产");
        }
        List<com.aimanga.v2.dto.TaskVO> tasks = new java.util.ArrayList<>();
        if (!characterIds.isEmpty()) {
            tasks.add(taskService.create(new com.aimanga.v2.dto.CreateTaskRequest(
                    projectId, null, "SHEET", batchPayload(characterIds))));
        }
        if (!refIds.isEmpty()) {
            tasks.add(taskService.create(new com.aimanga.v2.dto.CreateTaskRequest(
                    projectId, null, "ASSET_REF", batchPayload(refIds))));
        }
        return Result.ok(tasks);
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
