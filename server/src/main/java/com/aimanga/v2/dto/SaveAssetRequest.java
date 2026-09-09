package com.aimanga.v2.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SaveAssetRequest(
        @NotNull(message = "资产类型不能为空")
        @Min(value = 1, message = "资产类型 1角色 2场景 3道具 4服装")
        @Max(value = 4, message = "资产类型 1角色 2场景 3道具 4服装")
        Integer assetType,
        @NotBlank(message = "名称不能为空") String name,
        String aliases,
        String description,
        String structured,
        String referenceUrl) {
}
