package com.aimanga.v2.dto;

import jakarta.validation.constraints.NotBlank;

/** 粘贴文本创建作品 */
public record CreateProjectRequest(
        @NotBlank(message = "标题不能为空") String title,
        @NotBlank(message = "故事原文不能为空") String sourceText,
        String aspectRatio,
        String colorMode,
        Long stylePresetId) {
}
