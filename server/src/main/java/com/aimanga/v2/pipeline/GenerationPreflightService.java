package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.Asset;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.PageAssetRef;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.repository.AssetMapper;
import com.aimanga.v2.repository.ChapterMapper;
import com.aimanga.v2.repository.PageAssetRefMapper;
import com.aimanga.v2.repository.PageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出图素材预检(Phase 6.1 T6.1.4/T6.1.5):
 * 正式出图(BATCH)前检查目标范围的页/话/素材准备情况。
 *
 * Gate 规则(character_required):
 * - 页面引用的角色:必须至少存在一种视觉参考(sheet_image_url 优先,reference_url 兜底),否则 required missing → 阻止 BATCH;
 * - 场景/道具/服装:缺 reference_url 仅警告(允许继续,用文字设定生成);
 * - 绝不允许 BATCH 静默自动补生成缺失素材(素材由用户自行选择生成)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationPreflightService {

    private final PageMapper pageMapper;
    private final ChapterMapper chapterMapper;
    private final AssetMapper assetMapper;
    private final PageAssetRefMapper refMapper;

    /** 预检结果 */
    public record PreflightAsset(Long id, String name, Integer assetType) {}

    /** 分类统计:total=绑定数,ready=有视觉参考数 */
    public record TypeStat(Integer assetType, long total, long ready) {}

    public record PreflightResult(
            boolean ready,
            long pageCount,
            long requiredAssets,
            long readyAssets,
            List<PreflightAsset> missingRequiredAssets,
            List<PreflightAsset> optionalMissingAssets,
            List<TypeStat> stats,
            List<String> warnings) {}

    /**
     * 预检:chapterId 为空 = 整部作品;非空 = 指定话。
     */
    public PreflightResult preflight(Long projectId, Long chapterId) {
        List<String> warnings = new ArrayList<>();

        // 1. 目标页面
        List<PageEntity> pages = pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                .eq(PageEntity::getProjectId, projectId)
                .eq(chapterId != null, PageEntity::getChapterId, chapterId)
                .orderByAsc(PageEntity::getChapterId)
                .orderByAsc(PageEntity::getPageNo));
        if (pages.isEmpty()) {
            warnings.add("目标范围没有页面,请先完成脚本生成");
        }

        // 2. 话脚本就绪检查
        List<Chapter> chapters = chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getProjectId, projectId)
                .eq(chapterId != null, Chapter::getId, chapterId));
        long notReady = chapters.stream()
                .filter(c -> c.getStatus() == null || c.getStatus() < Chapter.STATUS_SCRIPT_READY)
                .count();
        if (notReady > 0) {
            warnings.add(notReady + " 话脚本尚未生成完成");
        }

        // 3. 页-素材绑定 → 汇总涉及的资产与必需/可选
        List<PageAssetRef> refs = pages.isEmpty() ? List.of()
                : refMapper.selectList(new LambdaQueryWrapper<PageAssetRef>()
                        .eq(PageAssetRef::getProjectId, projectId)
                        .in(PageAssetRef::getPageId, pages.stream().map(PageEntity::getId).toList()));
        long unboundPages = pages.size() - refs.stream().map(PageAssetRef::getPageId).distinct().count();
        if (unboundPages > 0) {
            warnings.add(unboundPages + " 页没有素材绑定,建议执行「重建绑定」后重新预检");
        }

        // 4. 资产准备情况
        Map<Long, Integer> required = new LinkedHashMap<>();
        Map<Long, Integer> optional = new LinkedHashMap<>();
        for (PageAssetRef ref : refs) {
            (ref.getRequiredFlag() != null && ref.getRequiredFlag() == 1 ? required : optional)
                    .merge(ref.getAssetId(), 1, Integer::sum);
        }
        List<Asset> involved = required.isEmpty() && optional.isEmpty() ? List.of()
                : assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getProjectId, projectId)
                        .in(Asset::getId, mergeKeys(required, optional)));
        Map<Long, Asset> assetMap = new LinkedHashMap<>();
        involved.forEach(a -> assetMap.put(a.getId(), a));

        List<PreflightAsset> missingRequired = new ArrayList<>();
        List<PreflightAsset> optionalMissing = new ArrayList<>();
        Map<Integer, long[]> byType = new LinkedHashMap<>();
        for (Long assetId : required.keySet()) {
            Asset asset = assetMap.get(assetId);
            if (asset == null) {
                continue; // 资产已被删除但引用未清理(FK 级联会清理,防御性跳过)
            }
            boolean hasVisual = notBlank(asset.getSheetImageUrl()) || notBlank(asset.getReferenceUrl());
            long[] stat = byType.computeIfAbsent(asset.getAssetType(), k -> new long[2]);
            stat[0]++;
            if (hasVisual) {
                stat[1]++;
            } else {
                missingRequired.add(new PreflightAsset(asset.getId(), asset.getName(), asset.getAssetType()));
            }
        }
        for (Long assetId : optional.keySet()) {
            Asset asset = assetMap.get(assetId);
            if (asset == null) {
                continue;
            }
            boolean hasVisual = notBlank(asset.getReferenceUrl());
            long[] stat = byType.computeIfAbsent(asset.getAssetType(), k -> new long[2]);
            stat[0]++;
            if (hasVisual) {
                stat[1]++;
            } else {
                optionalMissing.add(new PreflightAsset(asset.getId(), asset.getName(), asset.getAssetType()));
            }
        }
        if (!optionalMissing.isEmpty()) {
            warnings.add(optionalMissing.size() + " 个场景/道具/服装缺少参考图,将以文字设定生成(可先在资产库生成参考图)");
        }

        List<TypeStat> stats = byType.entrySet().stream()
                .map(e -> new TypeStat(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();

        boolean ready = pages.size() > 0 && missingRequired.isEmpty();
        return new PreflightResult(ready, pages.size(), required.size(), required.size() - missingRequired.size(),
                missingRequired, optionalMissing, stats, warnings);
    }

    private List<Long> mergeKeys(Map<Long, Integer> a, Map<Long, Integer> b) {
        List<Long> keys = new ArrayList<>(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
