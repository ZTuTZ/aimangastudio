package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.Asset;
import com.aimanga.v2.model.PageAssetRef;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.StylePreset;
import com.aimanga.v2.repository.AssetMapper;
import com.aimanga.v2.repository.PageAssetRefMapper;
import com.aimanga.v2.repository.StylePresetMapper;
import com.aimanga.v2.service.ConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 页级参考图解析器(Phase 6.2 T6.2.1):
 * 输入 pageId,按一致性优先级输出参考图 URL 列表(受 page_reference_max_images 截断,默认 8):
 *
 * 1. 本页角色的 sheet_image_url(身份约束,最高优先级)
 * 2. 角色 reference_url(兜底)
 * 3. 场景 reference_url(空间/背景约束)
 * 4. 服装 reference_url(穿着约束)
 * 5. 关键道具 reference_url(物件约束)
 * 6. style_preset.ref_images(风格约束)
 *
 * 最终页(PAGE)阶段由 PageGenerationService 在此基础上把 layout_image_url 置顶(构图约束)。
 * 绑定来自 page_asset_ref,天然按页收窄,不会把全项目素材塞给每一页。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageReferenceResolver {

    private final AssetMapper assetMapper;
    private final PageAssetRefMapper refMapper;
    private final StylePresetMapper stylePresetMapper;
    private final ConfigService configService;

    /** 参考图结果:有序 URL(对应 AI images 参数顺序)+ 素材描述行(prompt 用) */
    public record ResolvedReferences(List<String> imageUrls, List<String> assetLabels, int maxImages) {}

    public ResolvedReferences resolve(Long projectId, Long pageId, Project project) {
        int max = Math.max(1, configService.getInt("page_reference_max_images", 8));
        List<PageAssetRef> refs = refMapper.selectList(new LambdaQueryWrapper<PageAssetRef>()
                .eq(PageAssetRef::getPageId, pageId)
                .orderByAsc(PageAssetRef::getSortOrder));

        List<Asset> involved = refs.isEmpty() ? List.of()
                : assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getProjectId, projectId)
                        .in(Asset::getId, refs.stream().map(PageAssetRef::getAssetId).toList()));
        Map<Long, Asset> assetMap = involved.stream()
                .collect(Collectors.toMap(Asset::getId, Function.identity(), (a, b) -> a));

        List<PageAssetRef> characterRefs = refs.stream()
                .filter(r -> r.getRequiredFlag() != null && r.getRequiredFlag() == 1)
                .toList();
        List<PageAssetRef> optionalRefs = refs.stream()
                .filter(r -> r.getRequiredFlag() == null || r.getRequiredFlag() != 1)
                .sorted(Comparator
                        .comparing((PageAssetRef r) -> typeRank(assetMap, r))
                        .thenComparing(PageAssetRef::getSortOrder))
                .toList();

        List<String> urls = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        // ① 角色 sheet → ② 角色 reference(必需,缺图记录在案)
        for (PageAssetRef ref : characterRefs) {
            Asset asset = assetMap.get(ref.getAssetId());
            if (asset == null) continue;
            String url = firstNonBlank(asset.getSheetImageUrl(), asset.getReferenceUrl());
            if (url != null && urls.size() < max && !urls.contains(url)) {
                urls.add(url);
                labels.add("角色「" + asset.getName() + "」= 参考图第 " + urls.size() + " 张");
            } else {
                labels.add("角色「" + asset.getName() + "」= 未提供参考图,按文字设定严格保持形象");
            }
        }
        // ③ 场景 → ④ 服装 → ⑤ 道具
        for (PageAssetRef ref : optionalRefs) {
            Asset asset = assetMap.get(ref.getAssetId());
            if (asset == null) continue;
            String url = firstNonBlank(asset.getReferenceUrl(), asset.getSheetImageUrl());
            if (url != null && urls.size() < max && !urls.contains(url)) {
                urls.add(url);
                labels.add(typeName(asset.getAssetType()) + "「" + asset.getName() + "」= 参考图第 " + urls.size() + " 张");
            } else {
                labels.add(typeName(asset.getAssetType()) + "「" + asset.getName() + "」= 按文字设定");
            }
        }
        // ⑥ 风格参考图
        if (project.getStylePresetId() != null && urls.size() < max) {
            StylePreset preset = stylePresetMapper.selectById(project.getStylePresetId());
            if (preset != null && preset.getRefImages() != null && !preset.getRefImages().isBlank()) {
                for (String url : parseRefImages(preset.getRefImages())) {
                    if (urls.size() >= max) break;
                    if (!urls.contains(url)) {
                        urls.add(url);
                    }
                }
            }
        }

        log.info("[ref-resolver] 作品 {} 页 {}: 命中素材 {} 个,输出参考图 {}/{}", projectId, pageId, refs.size(), urls.size(), max);
        return new ResolvedReferences(urls, labels, max);
    }

    private int typeRank(Map<Long, Asset> assetMap, PageAssetRef ref) {
        Asset asset = assetMap.get(ref.getAssetId());
        if (asset == null || asset.getAssetType() == null) return 9;
        return switch (asset.getAssetType()) {
            case Asset.TYPE_SCENE -> 1;   // 场景先于服装
            case Asset.TYPE_OUTFIT -> 2;
            case Asset.TYPE_PROP -> 3;
            default -> 9;
        };
    }

    private static String typeName(Integer type) {
        if (type == null) return "素材";
        return switch (type) {
            case Asset.TYPE_SCENE -> "场景";
            case Asset.TYPE_PROP -> "道具";
            case Asset.TYPE_OUTFIT -> "服装";
            default -> "素材";
        };
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private List<String> parseRefImages(String json) {
        try {
            JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            List<String> list = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(n -> {
                    if (n.isTextual() && !n.asText().isBlank()) list.add(n.asText());
                });
            }
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }
}
