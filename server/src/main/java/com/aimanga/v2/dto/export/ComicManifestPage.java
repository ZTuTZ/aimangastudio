package com.aimanga.v2.dto.export;

/**
 * manifest 页节点:仅含 generate_status=2 且 generated_image_url 非空的正式成品页。
 * image_url 为 OSS 正式 URL;file_path 为 ZIP 内相对路径(如 "第1话/第1页.png")。
 */
public record ComicManifestPage(
        Integer pageNo,
        String imageUrl,
        String filePath) {
}
