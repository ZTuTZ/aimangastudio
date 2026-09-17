package com.aimanga.v2.dto.export;

import java.util.List;
import java.util.Map;

/**
 * manifest 页节点:仅含 generate_status=2 且 generated_image_url 非空的正式成品页。
 * image_url 为 OSS 正式 URL;file_path 为 ZIP 内相对路径(如 "第1话/第1页.png")。
 * textLayer 为 comic-text-layer-1.0 动态对白层(Phase 7.8);无文本层时为 null,不影响旧包兼容。
 */
public record ComicManifestPage(
        Integer pageNo,
        String imageUrl,
        String filePath,
        TextLayer textLayer) {

    public record TextLayer(String schemaVersion, List<Map<String, Object>> elements) {
    }
}
