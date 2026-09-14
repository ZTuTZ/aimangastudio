package com.aimanga.v2.dto;

public record PageVO(
        Long id,
        Long chapterId,
        Integer pageNo,
        String narration,
        String dialogue,
        String visual,
        String sceneDescription,
        String layoutImageUrl,
        String generatedImageUrl,
        String colorMode,
        Integer generateStatus,
        String failReason,
        Integer scriptVersion,
        Integer layoutScriptVersion,
        Integer imageScriptVersion,
        String generateRecords) {
}
