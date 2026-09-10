package com.aimanga.v2.pipeline.text;

import java.util.ArrayList;
import java.util.List;

/**
 * 原文确定性索引器(纯 Java,不调用 AI):
 * - 优先在段落/换行/句末标点处断开,单个 unit 可视正文约 80~500 字;
 * - 所有 unit 的 offset 连续且重建后与原文逐字符一致;
 * - SPLIT 滚动分包与 SCRIPT 页级 Source Spine 都基于本索引。
 */
public final class SourceTextIndexer {

    private static final int MIN_CHARS = 80;
    private static final int MAX_CHARS = 500;

    private SourceTextIndexer() {
    }

    public static List<SourceUnit> index(String source) {
        List<SourceUnit> units = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return units;
        }
        // 1. 以换行为界切成"段落原子段"(每段含其尾部换行,保证 offset 连续)
        List<int[]> atoms = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                atoms.add(new int[]{start, i + 1});
                start = i + 1;
            }
        }
        if (start < source.length()) {
            atoms.add(new int[]{start, source.length()});
        }

        // 2. 超长原子段(>MAX_CHARS)按句末标点切分(。！？!?；;),无标点硬切
        List<int[]> pieces = new ArrayList<>();
        for (int[] atom : atoms) {
            int len = atom[1] - atom[0];
            if (len <= MAX_CHARS) {
                pieces.add(atom);
                continue;
            }
            int pieceStart = atom[0];
            int lastBreak = -1;
            for (int i = atom[0]; i < atom[1]; i++) {
                char c = source.charAt(i);
                if (c == '。' || c == '！' || c == '?' || c == '？' || c == '！' || c == ';' || c == '；' || c == '.' || c == '!') {
                    lastBreak = i;
                }
                if (i - pieceStart + 1 >= MAX_CHARS) {
                    int cut = (lastBreak >= pieceStart && lastBreak + 1 - pieceStart >= MIN_CHARS / 2)
                            ? lastBreak + 1 : i + 1;
                    pieces.add(new int[]{pieceStart, cut});
                    pieceStart = cut;
                    lastBreak = -1;
                }
            }
            if (pieceStart < atom[1]) {
                pieces.add(new int[]{pieceStart, atom[1]});
            }
        }

        // 3. 顺序合并:累计到 MIN_CHARS 以上即输出一个 unit;单 unit 不超过 MAX_CHARS(超长原子段已在步骤2切分)
        int unitStart = 0;
        int accumulated = 0;
        for (int[] piece : pieces) {
            if (accumulated > 0 && accumulated + (piece[1] - piece[0]) > MAX_CHARS && accumulated >= MIN_CHARS) {
                emit(units, source, unitStart, piece[0]);
                unitStart = piece[0];
                accumulated = 0;
            }
            accumulated += piece[1] - piece[0];
            if (accumulated >= MIN_CHARS) {
                emit(units, source, unitStart, piece[1]);
                unitStart = piece[1];
                accumulated = 0;
            }
        }
        if (unitStart < source.length()) {
            emit(units, source, unitStart, source.length());
        }
        return units;
    }

    private static void emit(List<SourceUnit> units, String source, int start, int end) {
        if (end <= start) {
            return;
        }
        units.add(new SourceUnit(units.size() + 1, start, end, source.substring(start, end)));
    }

    /** 单元列表转 AI 展示文本:U0001|内容 */
    public static String toNumberedText(List<SourceUnit> units, int fromIndex, int toIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = fromIndex; i < toIndex; i++) {
            SourceUnit unit = units.get(i);
            sb.append(String.format("U%04d", unit.index())).append('|')
                    .append(unit.text().stripTrailing()).append('\n');
        }
        return sb.toString();
    }
}
