package com.aimanga.v2.service;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.dto.AssetVO;
import com.aimanga.v2.dto.SaveAssetRequest;
import com.aimanga.v2.model.Asset;
import com.aimanga.v2.repository.AssetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AssetService extends ServiceImpl<AssetMapper, Asset> {

    private final ProjectService projectService;

    public List<AssetVO> listByProject(Long projectId) {
        projectService.requireAccessible(projectId);
        return list(new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getProjectId, projectId)
                        .orderByAsc(Asset::getAssetType)
                        .orderByAsc(Asset::getId))
                .stream().map(this::toVO).toList();
    }

    public Asset create(Long projectId, SaveAssetRequest request) {
        projectService.requireAccessible(projectId);
        Long exists = baseMapper.selectCount(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, projectId)
                .eq(Asset::getAssetType, request.assetType())
                .eq(Asset::getName, request.name()));
        if (exists != null && exists > 0) {
            throw new BusinessException(409, "同名资产已存在: " + request.name());
        }
        Asset asset = new Asset();
        asset.setProjectId(projectId);
        asset.setAssetType(request.assetType());
        asset.setName(request.name());
        asset.setAliases(orEmptyJson(request.aliases()));
        asset.setDescription(request.description());
        asset.setStructured(orEmptyJson(request.structured()));
        asset.setReferenceUrl(request.referenceUrl());
        asset.setGenStatus(Asset.GEN_IDLE);
        asset.setCreateTime(LocalDateTime.now());
        save(asset);
        return asset;
    }

    public Asset requireAccessible(Long assetId) {
        Asset asset = getById(assetId);
        if (asset == null) {
            throw new BusinessException(404, "资产不存在: " + assetId);
        }
        projectService.requireAccessible(asset.getProjectId());
        return asset;
    }

    public Asset update(Long assetId, SaveAssetRequest request) {
        Asset asset = requireAccessible(assetId);
        Asset patch = new Asset();
        patch.setId(assetId);
        if (request.name() != null && !request.name().isBlank()) {
            patch.setName(request.name());
        }
        if (request.aliases() != null) {
            patch.setAliases(request.aliases().isBlank() ? "[]" : request.aliases());
        }
        if (request.description() != null) {
            patch.setDescription(request.description());
        }
        if (request.structured() != null) {
            patch.setStructured(request.structured().isBlank() ? "{}" : request.structured());
        }
        if (request.referenceUrl() != null) {
            patch.setReferenceUrl(request.referenceUrl());
        }
        patch.setUpdateTime(LocalDateTime.now());
        updateById(patch);
        return getById(assetId);
    }

    public void delete(Long assetId) {
        requireAccessible(assetId);
        removeById(assetId);
    }

    public AssetVO toVO(Asset asset) {
        return new AssetVO(asset.getId(), asset.getAssetType(), asset.getName(), asset.getAliases(),
                asset.getDescription(), asset.getStructured(), asset.getReferenceUrl(),
                asset.getSheetImageUrl(), asset.getGenStatus());
    }

    private static String orEmptyJson(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }
}
