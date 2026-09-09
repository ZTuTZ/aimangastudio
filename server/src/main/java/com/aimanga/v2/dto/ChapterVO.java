package com.aimanga.v2.dto;

import java.time.LocalDateTime;

public record ChapterVO(
        Long id,
        Long projectId,
        Integer chapterNo,
        String title,
        String scriptText,
        Integer status,
        Integer pageCount,
        LocalDateTime updateTime) {
}
