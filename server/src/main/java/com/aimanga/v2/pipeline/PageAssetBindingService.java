package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.Asset;
import com.aimanga.v2.model.PageAssetRef;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.repository.AssetMapper;
import com.aimanga.v2.repository.PageAssetRefMapper;
import com.aimanga.v2.repository.PageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 页级素材绑定服务(Phase 6.1):
 * Page(对白 speaker / visual / scene_description) → 资产 name/aliases 匹配 → page_asset_ref。
 *
 * - AI 返回的 assetIds 先校验归属并过滤非法,再与 Java name/alias 匹配结果合并(来源标记 AI/MATCH);
 * - 角色 required_flag=1(出图必需),场景/道具/服装 required_flag=0(可选,缺图仅警告);
 * - 旧脚本页面不需要重跑 SCRIPT:rebuildForProject / rebuildPage 一次性按现有文本重建绑定;
 * - 用户编辑页脚本后调用 rebuildPage 保持绑定同步(T6.5.5)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageAssetBindingService {

    private final PageMapper pageMapper;
    private final AssetMapper assetMapper;
    private final PageAssetRefMapper refMapper;
    private final ObjectMapper objectMapper;

    /** 单页绑定:AI 命中 + 程序匹配合并,全量替换该页绑定 */
    public int bindPage(PageEntity page, List<Asset> projectAssets, List<Long> aiAssetIds) {
        Map<Long, String> sources = new LinkedHashMap<>();
        // ① AI 返回的 assetIds:校验属于当前 project 后采纳(source=AI)
        if (aiAssetIds != null) {
            for (Long id : aiAssetIds) {
                if (id != null && projectAssets.stream().anyMatch(a -> a.getId().equals(id))) {
                    sources.putIfAbsent(id, PageAssetRef.SOURCE_AI);
                }
            }
        }
        // ② Java name/alias 匹配(source=MATCH)
        String speakerText = speakerText(page.getDialogue());
        String text = speakerText + "\n" + safe(page.getVisual()) + "\n" + safe(page.getSceneDescription());
        for (Asset asset : projectAssets) {
            boolean hit = notBlank(asset.getName()) && text.contains(asset.getName().trim())
                    || aliasesHit(asset, text);
            if (hit) {
                sources.putIfAbsent(asset.getId(), PageAssetRef.SOURCE_MATCH);
            }
        }
        // ③ 全量替换该页绑定
        refMapper.delete(new LambdaQueryWrapper<PageAssetRef>().eq(PageAssetRef::getPageId, page.getId()));
        if (sources.isEmpty()) {
            return 0;
        }
        // 排序:角色优先(类型升序),再按资产 id
        List<Asset> ordered = projectAssets.stream()
                .filter(a -> sources.containsKey(a.getId()))
                .sorted(Comparator.comparing(Asset::getAssetType, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(Asset::getId))
                .toList();
        int sort = 0;
        for (Asset asset : ordered) {
            PageAssetRef ref = new PageAssetRef();
            ref.setProjectId(page.getProjectId());
            ref.setPageId(page.getId());
            ref.setAssetId(asset.getId());
            ref.setRequiredFlag(asset.getAssetType() != null && asset.getAssetType() == Asset.TYPE_CHARACTER ? 1 : 0);
            ref.setSource(sources.get(asset.getId()));
            ref.setSortOrder(sort++);
            ref.setCreateTime(LocalDateTime.now());
            refMapper.insert(ref);
        }
        return ordered.size();
    }

    /** 重建单页绑定(纯程序匹配,无 AI 结果)——用户编辑页脚本后调用(T6.5.5) */
    public int rebuildPage(Long pageId) {
        PageEntity page = pageMapper.selectById(pageId);
        if (page == null) {
            return 0;
        }
        List<Asset> assets = projectAssets(page.getProjectId());
        return bindPage(page, assets, List.of());
    }

    /** 重建整部作品绑定(兼容已生成的旧脚本页,无需重跑 SCRIPT) */
    public int rebuildForProject(Long projectId) {
        List<PageEntity> pages = pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                .eq(PageEntity::getProjectId, projectId));
        List<Asset> assets = projectAssets(projectId);
        int total = 0;
        for (PageEntity page : pages) {
            total += bindPage(page, assets, List.of());
        }
        log.info("[binding] 作品 {} 重建页-素材绑定: {} 页 / {} 条引用", projectId, pages.size(), total);
        return total;
    }

    /** 查询页绑定的资产引用 */
    public List<PageAssetRef> refsOfPage(Long pageId) {
        return refMapper.selectList(new LambdaQueryWrapper<PageAssetRef>()
                .eq(PageAssetRef::getPageId, pageId)
                .orderByAsc(PageAssetRef::getSortOrder));
    }

    private List<Asset> projectAssets(Long projectId) {
        return assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, projectId)
                .orderByAsc(Asset::getAssetType)
                .orderByAsc(Asset::getId));
    }

    /** 对白 JSON → speaker 文本(speaker 是角色匹配的首要依据) */
    private String speakerText(String dialogueJson) {
        if (dialogueJson == null || dialogueJson.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(dialogueJson);
            StringBuilder sb = new StringBuilder();
            if (node.isArray()) {
                for (JsonNode n : node) {
                    JsonNode speaker = n.get("speaker");
                    if (speaker != null && speaker.isTextual()) {
                        sb.append(speaker.asText()).append("\n");
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean aliasesHit(Asset asset, String text) {
        for (String alias : parseAliases(asset.getAliases())) {
            if (text.contains(alias)) {
                return true;
            }
        }
        return false;
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

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
