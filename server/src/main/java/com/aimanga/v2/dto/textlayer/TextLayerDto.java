package com.aimanga.v2.dto.textlayer;

import java.util.List;

/**
 * Comic Text Layer Schema v1(Phase 7.8):底图与文本层的解耦协议。
 * 坐标/字号全部为归一化比例值(0~1),未来 APP/小程序直接消费同一结构。
 */
public record TextLayerDto(
        String schemaVersion,
        Long pageId,
        Integer pageVersion,
        Integer textLayoutSourceVersion,
        List<Element> elements) {

    public static final String SCHEMA_VERSION = "comic-text-layer-1.0";

    public record Element(
            String uid,
            String type,
            Integer dialogueIndex,
            String speaker,
            String text,
            Position position,
            Style style,
            Bubble bubble,
            Integer sortOrder) {
    }

    public record Position(double x, double y, double width, Double height) {
    }

    public record Style(String fontPreset, Double fontSizeRatio, String align, Integer maxLines) {
    }

    public record Bubble(String preset, Tail tail) {
    }

    public record Tail(Double x, Double y) {
    }
}
