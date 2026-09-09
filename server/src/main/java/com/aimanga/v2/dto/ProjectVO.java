package com.aimanga.v2.dto;

import java.time.LocalDateTime;

/** 作品卡片/详情 */
public record ProjectVO(
        Long id,
        String title,
        Integer status,
        String aspectRatio,
        String colorMode,
        Long stylePresetId,
        String tagline,
        String sourceText,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        Long chapterCount,
        Long pageCount) {

    /** 列表视图:不含大字段 sourceText */
    public static ProjectVO brief(ProjectVO vo) {
        return new ProjectVO(vo.id(), vo.title(), vo.status(), vo.aspectRatio(), vo.colorMode(),
                vo.stylePresetId(), vo.tagline(), null, vo.createTime(), vo.updateTime(),
                vo.chapterCount(), vo.pageCount());
    }
}
