package com.aimanga.v2.controller;

import com.aimanga.v2.common.Result;
import com.aimanga.v2.dto.AssetVO;
import com.aimanga.v2.dto.SaveAssetRequest;
import com.aimanga.v2.model.Asset;
import com.aimanga.v2.service.AssetService;
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
}
