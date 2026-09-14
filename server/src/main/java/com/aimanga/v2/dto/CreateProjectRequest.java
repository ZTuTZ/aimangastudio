package com.aimanga.v2.dto;

import jakarta.validation.constraints.NotBlank;

/** 粘贴文本创建作品 */
public record CreateProjectRequest(
        @NotBlank(message = "标题不能为空") String title,
        @NotBlank(message = "故事原文不能为空") String sourceText,
        String aspectRatio,
        String colorMode,
        Long stylePresetId,
        /** 素材参考图画幅:场景(默认 16:9) */
        String sceneRatio,
        /** 素材参考图画幅:道具(默认 1:1) */
        String propRatio,
        /** 素材参考图画幅:服装(默认 3:4) */
        String costumeRatio) {
}
