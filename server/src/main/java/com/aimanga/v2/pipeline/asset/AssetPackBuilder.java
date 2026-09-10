package com.aimanga.v2.pipeline.asset;

import com.aimanga.v2.model.Chapter;

import java.util.ArrayList;
import java.util.List;

/** 资产分包:按 chapter_no ASC 分组,每包最多 maxChapters 话且约 maxChars 字(先到者结束);超长话独占一包完整发送 */
public final class AssetPackBuilder {

    private AssetPackBuilder() {
    }

    public static List<List<Chapter>> build(List<Chapter> chapters, int maxChapters, int maxChars) {
        List<List<Chapter>> packs = new ArrayList<>();
        List<Chapter> current = new ArrayList<>();
        int chars = 0;
        for (Chapter chapter : chapters) {
            int len = chapter.getScriptText() == null ? 0 : chapter.getScriptText().length();
            if (!current.isEmpty() && (current.size() >= maxChapters || chars + len > maxChars)) {
                packs.add(current);
                current = new ArrayList<>();
                chars = 0;
            }
            current.add(chapter);
            chars += len;
            if (len > maxChars) {
                // 单话超长:独占一包完整发送(正常 SPLIT 设计下不应发生)
                packs.add(current);
                current = new ArrayList<>();
                chars = 0;
            }
        }
        if (!current.isEmpty()) {
            packs.add(current);
        }
        return packs;
    }
}
