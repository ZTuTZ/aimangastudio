package com.aimanga.v2.pipeline;

import java.util.List;

/**
 * 资产提取契约(ASSET 任务产物):四类资产 1角色 2场景 3道具 4服装。
 * 角色建议带 structured;场景/道具/服装 structured 可为 null。
 */
public record AssetExtractResult(List<AssetItem> assets) {

    public record AssetItem(
            Integer assetType,
            String name,
            List<String> aliases,
            String description,
            Structured structured) {
    }

    public record Structured(
            String role,
            String age,
            String hair,
            String accessories,
            String top,
            String bottom) {
    }
}
