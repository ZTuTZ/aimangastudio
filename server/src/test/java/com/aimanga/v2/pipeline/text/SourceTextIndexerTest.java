package com.aimanga.v2.pipeline.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceTextIndexerTest {

    private void assertReconstructs(String source) {
        List<SourceUnit> units = SourceTextIndexer.index(source);
        assertThat(units).isNotEmpty();
        // offset 连续性
        assertThat(units.get(0).startOffset()).isZero();
        for (int i = 0; i < units.size() - 1; i++) {
            assertThat(units.get(i).endOffset()).isEqualTo(units.get(i + 1).startOffset());
        }
        assertThat(units.get(units.size() - 1).endOffset()).isEqualTo(source.length());
        // 逐字符重建一致
        StringBuilder rebuilt = new StringBuilder();
        units.forEach(u -> rebuilt.append(u.text()));
        assertThat(rebuilt.toString()).isEqualTo(source);
        // index 1-based 连续
        for (int i = 0; i < units.size(); i++) {
            assertThat(units.get(i).index()).isEqualTo(i + 1);
        }
    }

    @Test
    void chineseNovelWithParagraphs() {
        String src = "深夜的旧书店里,林夏整理着书架。\n一本旧书自己翻开了。\n\n他想找回失去的记忆。\n于是他写下了“是”。\n";
        List<SourceUnit> units = SourceTextIndexer.index(src);
        assertReconstructs(src);
        assertThat(units.size()).isGreaterThanOrEqualTo(1);
        // 单元粒度 80~500 建议(末尾合并后可能 <80,这里总长 60 字左右应合成 1 个)
        assertThat(units.get(0).text()).doesNotContain("\u0000");
    }

    @Test
    void windowsCrLf() {
        String src = "第一段内容。\r\n第二段内容。\r\n第三段内容。\r\n";
        assertReconstructs(src);
    }

    @Test
    void consecutiveBlankLines() {
        String src = "段落一。\n\n\n\n段落二。\n\n\n段落三。\n";
        assertReconstructs(src);
    }

    @Test
    void singleHugeParagraphNoNewline() {
        String sentence = "这是一个非常长的句子,用来测试超长段落切分逻辑,";
        String src = sentence.repeat(60); // ~1900 字,无换行
        assertReconstructs(src);
        List<SourceUnit> units = SourceTextIndexer.index(src);
        assertThat(units.size()).isGreaterThanOrEqualTo(3);
        for (SourceUnit unit : units) {
            assertThat(unit.text().length()).isLessThanOrEqualTo(500 + 5);
        }
    }

    @Test
    void mixedChineseEnglish() {
        String src = "He said: 再见 my friend。\nThe book costs 12.5 元,ok?\n林夏 replied: see you!\n";
        assertReconstructs(src);
    }

    @Test
    void emojiAndUnicode() {
        String src = "🌸 春天来了,樱花开了 🌸。\n妹妹说：要替我看看春天 😀。\n\n加油💪！";
        assertReconstructs(src);
    }

    @Test
    void longNovelScale() {
        StringBuilder src = new StringBuilder();
        for (int i = 1; i <= 300; i++) {
            src.append("第").append(i).append("段:这是第").append(i).append("段的内容,讲述故事推进的细节与对白。\n");
        }
        assertReconstructs(src.toString());
        List<SourceUnit> units = SourceTextIndexer.index(src.toString());
        assertThat(units.size()).isGreaterThan(10); // 9000 字按 80~500 字粒度应产出数十个单元
    }

    @Test
    void numberedTextFormat() {
        List<SourceUnit> units = SourceTextIndexer.index("第一段。\n第二段。\n");
        String numbered = SourceTextIndexer.toNumberedText(units, 0, units.size());
        assertThat(numbered).startsWith("U0001|"); // 短文本合并为单 unit 也必须从 U0001 编号
    }
}
