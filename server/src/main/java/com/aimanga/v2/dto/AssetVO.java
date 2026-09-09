package com.aimanga.v2.dto;

public record AssetVO(
        Long id,
        Integer assetType,
        String name,
        String aliases,
        String description,
        String structured,
        String referenceUrl,
        String sheetImageUrl,
        Integer genStatus) {
}
