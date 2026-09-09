package com.aimanga.v2.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 更新作品:contentUid 不在本请求中 —— 跨系统稳定 ID 禁止通过普通更新接口修改。
 */
public record UpdateProjectRequest(
        String title,
        String aspectRatio,
        String colorMode,
        Long stylePresetId,
        String tagline,
        String description,
        String coverUrl,
        String category,
        /** JSON 数组字符串,如 ["重生","系统"] */
        String tags,
        @Min(value = 1, message = "连载状态只能为 1(连载中) 或 2(已完结)")
        @Max(value = 2, message = "连载状态只能为 1(连载中) 或 2(已完结)")
        Integer seriesStatus) {
}
