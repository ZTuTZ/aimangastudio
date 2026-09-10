package com.aimanga.v2.pipeline.asset;

import com.aimanga.v2.model.Asset;
import com.aimanga.v2.pipeline.AssetExtractResult;
import com.aimanga.v2.repository.AssetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资产全局合并服务:
 * - mergePacks:多包 AI 结果 → canonical 列表(同 type 下 name/aliases 互相命中即合并,冲突保留先出现值);
 * - upsertAll:canonical 与 DB 合并(保护人工 reference_url/sheet_image_url/非空 description/structured;aliases union);
 * - 不跨 asset_type 合并;无法确定的模糊冲突保留两条并 WARN。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetMergeService {

    private final AssetMapper assetMapper;
    private final ObjectMapper objectMapper;

    /** 多包结果合并为 canonical 列表(纯内存,不写库) */
    public List<AssetExtractResult.AssetItem> mergePacks(List<AssetExtractResult> packResults) {
        List<AssetExtractResult.AssetItem> canonical = new ArrayList<>();
        int warnings = 0;
        for (AssetExtractResult result : packResults) {
            if (result == null || result.assets() == null) continue;
            for (AssetExtractResult.AssetItem item : result.assets()) {
                if (item.name() == null || item.name().isBlank()) continue;
                AssetExtractResult.AssetItem normalized = new AssetExtractResult.AssetItem(
                        item.assetType() == null ? Asset.TYPE_CHARACTER : item.assetType(),
                        item.name().trim(),
                        item.aliases() == null ? List.of() : item.aliases(),
                        item.description(),
                        item.structured());
                int idx = findCanonical(canonical, normalized);
                if (idx >= 0) {
                    canonical.set(idx, mergeInto(canonical.get(idx), normalized));
                } else {
                    canonical.add(normalized);
                }
            }
        }
        if (warnings > 0) {
            log.warn("[asset-merge] {} 个模糊名称未合并", warnings);
        }
        return canonical;
    }

    /** 查找 canonical:同 type 下 name 相等,或 name 命中对方 aliases(双向) */
    private int findCanonical(List<AssetExtractResult.AssetItem> canonical, AssetExtractResult.AssetItem item) {
        for (int i = 0; i < canonical.size(); i++) {
            AssetExtractResult.AssetItem c = canonical.get(i);
            if (c.assetType() != item.assetType()) continue; // 禁止跨类型合并(角色“白龙”≠道具“白龙剑”)
            if (c.name().equalsIgnoreCase(item.name())) return i;
            boolean aliasHit = c.aliases().stream().anyMatch(a -> a.equalsIgnoreCase(item.name()))
                    || item.aliases().stream().anyMatch(a -> a.equalsIgnoreCase(c.name()));
            if (aliasHit) return i;
        }
        return -1;
    }

    private AssetExtractResult.AssetItem mergeInto(AssetExtractResult.AssetItem c, AssetExtractResult.AssetItem item) {
        List<String> aliases = new ArrayList<>(c.aliases());
        for (String a : item.aliases()) {
            if (notBlank(a) && !a.equalsIgnoreCase(c.name()) && aliases.stream().noneMatch(x -> x.equalsIgnoreCase(a))) {
                aliases.add(a.trim());
            }
        }
        String description = longer(c.description(), item.description());
        AssetExtractResult.Structured structured = mergeStructured(c.structured(), item.structured());
        return new AssetExtractResult.AssetItem(c.assetType(), c.name(), aliases, description, structured);
    }

    private AssetExtractResult.Structured mergeStructured(AssetExtractResult.Structured base, AssetExtractResult.Structured extra) {
        if (base == null) return extra;
        if (extra == null) return base;
        // 冲突时保留第一(canonical)值
        return new AssetExtractResult.Structured(
                firstNonBlank(base.role(), extra.role()),
                firstNonBlank(base.age(), extra.age()),
                firstNonBlank(base.hair(), extra.hair()),
                firstNonBlank(base.accessories(), extra.accessories()),
                firstNonBlank(base.top(), extra.top()),
                firstNonBlank(base.bottom(), extra.bottom()));
    }

    /** canonical 与 DB 合并并落库(保护人工值;reference_url/sheet_image_url 永不覆盖) */
    public record MergeStats(int canonicalCount, int inserted, int updated) {
    }

    public MergeStats upsertAll(Long projectId, List<AssetExtractResult.AssetItem> canonical) {
        int inserted = 0;
        int updated = 0;
        for (AssetExtractResult.AssetItem item : canonical) {
            Asset existing = findByCanonical(projectId, item);
            if (existing == null) {
                Asset asset = new Asset();
                asset.setProjectId(projectId);
                asset.setAssetType(item.assetType());
                asset.setName(item.name());
                asset.setAliases(writeAliases(item.aliases()));
                asset.setDescription(item.description());
                asset.setStructured(item.structured() == null ? "{}" : writeStructured(item.structured()));
                asset.setGenStatus(Asset.GEN_IDLE);
                asset.setCreateTime(LocalDateTime.now());
                assetMapper.insert(asset);
                inserted++;
                continue;
            }
            Asset patch = new Asset();
            patch.setId(existing.getId());
            // 非空 description 不覆盖
            if (blank(existing.getDescription()) && notBlank(item.description())) {
                patch.setDescription(item.description());
            }
            // structured 只补缺失字段(角色)
            if (item.assetType() == Asset.TYPE_CHARACTER && item.structured() != null) {
                patch.setStructured(mergeStructuredJson(existing.getStructured(), item.structured()));
            }
            // aliases union
            List<String> mergedAliases = unionAliases(existing.getAliases(), item.aliases());
            try {
                patch.setAliases(objectMapper.writeValueAsString(mergedAliases));
            } catch (Exception ignored) {
                // 保留原值
            }
            patch.setUpdateTime(LocalDateTime.now());
            assetMapper.updateById(patch);
            updated++;
        }
        log.info("[asset-merge] canonical={} 新增={} 更新={}", canonical.size(), inserted, updated);
        return new MergeStats(canonical.size(), inserted, updated);
    }

    /** 按 canonical name 或 DB aliases 命中查找(不跨类型) */
    private Asset findByCanonical(Long projectId, AssetExtractResult.AssetItem item) {
        List<Asset> sameType = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, projectId)
                .eq(Asset::getAssetType, item.assetType()));
        for (Asset asset : sameType) {
            if (asset.getName().equalsIgnoreCase(item.name())) return asset;
        }
        for (Asset asset : sameType) {
            List<String> aliases = parseAliases(asset.getAliases());
            if (aliases.stream().anyMatch(a -> a.equalsIgnoreCase(item.name()))) return asset;
        }
        return null;
    }

    /** structured JSON 只补缺失字段,DB 非空值保留 */
    private String mergeStructuredJson(String dbJson, AssetExtractResult.Structured item) {
        try {
            Map<String, String> dbMap = new LinkedHashMap<>();
            JsonNode node = objectMapper.readTree(dbJson == null || dbJson.isBlank() ? "{}" : dbJson);
            node.fieldNames().forEachRemaining(k -> {
                JsonNode v = node.get(k);
                if (v.isTextual() && !v.asText().isBlank()) dbMap.put(k, v.asText());
            });
            fillIfAbsent(dbMap, "role", item.role());
            fillIfAbsent(dbMap, "age", item.age());
            fillIfAbsent(dbMap, "hair", item.hair());
            fillIfAbsent(dbMap, "accessories", item.accessories());
            fillIfAbsent(dbMap, "top", item.top());
            fillIfAbsent(dbMap, "bottom", item.bottom());
            return objectMapper.writeValueAsString(dbMap);
        } catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(item);
            } catch (Exception ignored) {
                return "{}";
            }
        }
    }

    private void fillIfAbsent(Map<String, String> map, String key, String value) {
        if (notBlank(value) && (!map.containsKey(key) || map.get(key).isBlank())) {
            map.put(key, value.trim());
        }
    }

    private List<String> unionAliases(String dbJson, List<String> itemAliases) {
        List<String> merged = new ArrayList<>(parseAliases(dbJson));
        for (String a : itemAliases) {
            if (notBlank(a) && merged.stream().noneMatch(x -> x.equalsIgnoreCase(a.trim()))) {
                merged.add(a.trim());
            }
        }
        return merged;
    }

    private List<String> parseAliases(String json) {
        try {
            JsonNode node = objectMapper.readTree(json == null || json.isBlank() ? "[]" : json);
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

    private static String longer(String a, String b) {
        if (a == null || a.isBlank()) return b;
        if (b == null || b.isBlank()) return a;
        return b.length() > a.length() ? b : a;
    }

    private static String firstNonBlank(String a, String b) {
        return notBlank(a) ? a : b;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean notBlank(String s) {
        return !blank(s);
    }

    private String writeAliases(List<String> aliases) {
        try {
            return objectMapper.writeValueAsString(aliases == null ? List.of() : aliases);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String writeStructured(AssetExtractResult.Structured s) {
        try {
            return objectMapper.writeValueAsString(s);
        } catch (Exception e) {
            return "{}";
        }
    }
}
