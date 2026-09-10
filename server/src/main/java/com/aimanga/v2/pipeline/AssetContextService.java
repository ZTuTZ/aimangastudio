package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.Asset;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.repository.AssetMapper;
import com.aimanga.v2.service.ConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SCRIPT 全书标准资产上下文:
 * 按本话正文命中(name/alias)筛选相关资产,角色优先;无命中角色时兜底前若干主角色;
 * 总数受 script_asset_context_max 限制,避免把上百个资产无脑塞给每一话。
 */
@Component
@RequiredArgsConstructor
public class AssetContextService {

    private final AssetMapper assetMapper;
    private final ObjectMapper objectMapper;
    private final ConfigService configService;

    public String buildScriptAssetContext(Project project, Chapter chapter) {
        int max = Math.max(3, configMax());
        List<Asset> all = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, project.getId())
                .orderByAsc(Asset::getAssetType)
                .orderByAsc(Asset::getId));
        if (all.isEmpty()) {
            return "（暂无标准资产,请按本话原文自行设计,并在 characters 中报告新人物）";
        }
        String text = chapter.getScriptText() == null ? "" : chapter.getScriptText();

        List<Asset> matchedCharacters = new ArrayList<>();
        List<Asset> matchedOthers = new ArrayList<>();
        List<Asset> fallbackCharacters = new ArrayList<>();
        for (Asset asset : all) {
            boolean hit = text.contains(asset.getName()) || aliasesHit(asset, text);
            if (asset.getAssetType() == Asset.TYPE_CHARACTER) {
                if (hit) matchedCharacters.add(asset);
                else fallbackCharacters.add(asset);
            } else if (hit) {
                matchedOthers.add(asset);
            }
        }

        List<String> lines = new ArrayList<>();
        int used = 0;
        for (Asset asset : matchedCharacters) {
            if (used >= max) break;
            lines.add(characterLine(asset));
            used++;
        }
        for (Asset asset : matchedOthers) {
            if (used >= max) break;
            lines.add(otherLine(asset));
            used++;
        }
        if (matchedCharacters.isEmpty() && !fallbackCharacters.isEmpty()) {
            for (Asset asset : fallbackCharacters) {
                if (used >= max) break;
                lines.add(characterLine(asset));
                used++;
            }
        }
        return String.join("\n", lines);
    }

    private String characterLine(Asset asset) {
        StringBuilder sb = new StringBuilder("- [角色] ").append(asset.getName());
        List<String> aliases = parseAliases(asset.getAliases());
        if (!aliases.isEmpty()) {
            sb.append("(又名:").append(String.join("/", aliases)).append(")");
        }
        String structured = compactStructured(asset.getStructured());
        if (!structured.isBlank()) {
            sb.append(" | ").append(structured);
        }
        if (asset.getDescription() != null && !asset.getDescription().isBlank()) {
            sb.append(" | ").append(clip(asset.getDescription(), 120));
        }
        return sb.toString();
    }

    private String otherLine(Asset asset) {
        String type = asset.getAssetType() == Asset.TYPE_SCENE ? "场景"
                : asset.getAssetType() == Asset.TYPE_PROP ? "道具" : "服装";
        StringBuilder sb = new StringBuilder("- [").append(type).append("] ").append(asset.getName());
        if (asset.getDescription() != null && !asset.getDescription().isBlank()) {
            sb.append(" | ").append(clip(asset.getDescription(), 120));
        }
        return sb.toString();
    }

    private String compactStructured(String structuredJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(
                    structuredJson == null || structuredJson.isBlank() ? "{}" : structuredJson);
            List<String> pairs = new ArrayList<>();
            node.fieldNames().forEachRemaining(k -> {
                JsonNode v = node.get(k);
                if (v.isTextual() && !v.asText().isBlank()) {
                    pairs.add(k + ":" + v.asText());
                }
            });
            return String.join(",", pairs);
        } catch (Exception e) {
            return "";
        }
    }

    private boolean aliasesHit(Asset asset, String text) {
        for (String alias : parseAliases(asset.getAliases())) {
            if (text.contains(alias)) return true;
        }
        return false;
    }

    private List<String> parseAliases(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(
                    json == null || json.isBlank() ? "[]" : json);
            List<String> list = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(n -> {
                    if (n.isTextual() && !n.asText().isBlank()) list.add(n.asText());
                });
            }
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private int configMax() {
        return configService.getInt("script_asset_context_max", 30);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
