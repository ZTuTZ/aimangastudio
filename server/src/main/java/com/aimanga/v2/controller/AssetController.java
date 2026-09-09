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
}
