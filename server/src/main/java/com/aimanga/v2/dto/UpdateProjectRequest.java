package com.aimanga.v2.dto;

public record UpdateProjectRequest(
        String title,
        String aspectRatio,
        String colorMode,
        Long stylePresetId,
        String tagline) {
}
