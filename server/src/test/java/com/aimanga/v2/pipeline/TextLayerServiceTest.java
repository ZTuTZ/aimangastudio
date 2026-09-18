package com.aimanga.v2.pipeline;

import com.aimanga.v2.dto.textlayer.TextLayerDto;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.PageTextElement;
import com.aimanga.v2.repository.PageMapper;
import com.aimanga.v2.repository.PageTextElementMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7.8 文本层服务单测:
 * 初始化规则布局 / 保存按 uid 增删改 / 脚本同步保留位置 / 重置。
 */
class TextLayerServiceTest {

    @Test
    void multiRowMutationsAreTransactional() throws Exception {
        assertThat(TextLayerService.class.getMethod("initializeFromPage", Long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(TextLayerService.class.getMethod("saveTextLayer", Long.class, TextLayerDto.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(TextLayerService.class.getMethod("resetTextLayer", Long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(TextLayerService.class.getMethod("syncFromPageContent", Long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    private PageMapper pageMapper;
    private PageTextElementMapper elementMapper;
    private TextLayerService service;
    private PageEntity page;

    @BeforeEach
    void setUp() {
        pageMapper = mock(PageMapper.class);
        elementMapper = mock(PageTextElementMapper.class);
        service = new TextLayerService(pageMapper, elementMapper, new ObjectMapper());
        page = new PageEntity();
        page.setId(10L);
        page.setProjectId(100L);
        page.setChapterId(5L);
        page.setPageNo(1);
        page.setScriptVersion(2);
        page.setTextLayoutVersion(0);
        page.setNarration("三日前,父亲的书房失窃。");
        page.setDialogue("""
                [{"speaker":"林凡","line":"这里是什么地方？"},
                 {"speaker":"苏雨","line":"你终于醒了。"}]
                """);
        when(pageMapper.selectById(10L)).thenReturn(page);
        when(elementMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    }

    private PageTextElement row(String uid, String type, Integer idx, String text) {
        PageTextElement r = new PageTextElement();
        r.setId(uid.hashCode() * -1L);
        r.setPageId(10L);
        r.setProjectId(100L);
        r.setChapterId(5L);
        r.setElementUid(uid);
        r.setElementType(type);
        r.setDialogueIndex(idx);
        r.setSpeaker(type.equals(PageTextElement.TYPE_DIALOGUE) ? "旧角色" : null);
        r.setTextContent(text);
        r.setX(0.1);
        r.setY(0.1);
        r.setWidth(0.3);
        r.setHeight(0.1);
        r.setBubbleStyle("DEFAULT_DIALOGUE");
        r.setFontStyle("DEFAULT_DIALOGUE");
        r.setFontSizeRatio(0.028);
        r.setTextAlign("CENTER");
        r.setMaxLines(4);
        r.setSortOrder(1);
        r.setSourceType(PageTextElement.SOURCE_AUTO);
        return r;
    }

    @Test
    void initialize_createsDefaultLayoutFromPageContent() {
        // 2 条对白 + 1 条旁白 → 3 个元素,对白左右交替,旁白底部
        TextLayerDto dto = service.initializeFromPage(10L);

        ArgumentCaptor<PageTextElement> captor = ArgumentCaptor.forClass(PageTextElement.class);
        verify(elementMapper, times(3)).insert(captor.capture());
        List<PageTextElement> rows = captor.getAllValues();
        assertThat(rows).extracting(PageTextElement::getElementType)
                .containsExactly(PageTextElement.TYPE_DIALOGUE, PageTextElement.TYPE_DIALOGUE,
                        PageTextElement.TYPE_NARRATION);
        assertThat(rows.get(0).getSpeaker()).isEqualTo("林凡");
        assertThat(rows.get(0).getX()).isLessThan(rows.get(1).getX()); // 左右交替
        assertThat(rows.get(2).getY()).isGreaterThan(0.8); // 旁白底部
        assertThat(rows.get(0).getSourceType()).isEqualTo(PageTextElement.SOURCE_AUTO);
        // 初始化后标记同步版本 = 脚本版本(通过 updateById patch 落库)
        org.mockito.ArgumentCaptor<com.aimanga.v2.model.PageEntity> patchCaptor =
                org.mockito.ArgumentCaptor.forClass(com.aimanga.v2.model.PageEntity.class);
        verify(pageMapper).updateById(patchCaptor.capture());
        assertThat(patchCaptor.getValue().getTextLayoutVersion()).isEqualTo(2);
        assertThat(dto.schemaVersion()).isEqualTo("comic-text-layer-1.0");
        assertThat(dto.pageId()).isEqualTo(10L);
    }

    @Test
    void initialize_idempotent_whenElementsExist() {
        when(elementMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(row("TXT_A", PageTextElement.TYPE_DIALOGUE, 0, "已有")));
        TextLayerDto dto = service.initializeFromPage(10L);
        verify(elementMapper, times(0)).insert(any(PageTextElement.class));
        assertThat(dto.elements()).hasSize(1);
    }

    @Test
    void save_upsertsByUid_andDeletesMissing() {
        when(elementMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(row("TXT_KEEP", PageTextElement.TYPE_DIALOGUE, 0, "保留")),
                        List.of());

        TextLayerDto dto = new TextLayerDto("comic-text-layer-1.0", 10L, 2, 0, List.of(
                new TextLayerDto.Element("TXT_KEEP", "DIALOGUE", 0, "林凡", "改过的台词",
                        new TextLayerDto.Position(0.2, 0.3, 0.3, 0.1),
                        new TextLayerDto.Style("DEFAULT_DIALOGUE", 0.03, "CENTER", 4),
                        new TextLayerDto.Bubble("DEFAULT_DIALOGUE", new TextLayerDto.Tail(0.35, 0.4)), 1),
                new TextLayerDto.Element("TXT_NEW", "NARRATION", null, null, "新旁白",
                        new TextLayerDto.Position(0.06, 0.86, 0.88, 0.09),
                        new TextLayerDto.Style("DEFAULT_NARRATION", 0.026, "LEFT", 3),
                        new TextLayerDto.Bubble("NARRATION_BOX", null), 2)));

        service.saveTextLayer(10L, dto);

        // TXT_NEW 新建(MANUAL);TXT_KEEP 更新;无删除
        ArgumentCaptor<PageTextElement> insertCaptor = ArgumentCaptor.forClass(PageTextElement.class);
        verify(elementMapper).insert(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getElementUid()).isEqualTo("TXT_NEW");
        assertThat(insertCaptor.getValue().getSourceType()).isEqualTo(PageTextElement.SOURCE_MANUAL);
        verify(elementMapper).updateById(any(PageTextElement.class));
        verify(elementMapper, times(0)).deleteBatchIds(any());
    }

    @Test
    void sync_updatesText_keepsPosition_andRemovesStaleDialogue() {
        // 已有:对白 idx0(位置保留)+对白 idx5(应被删);旁白文本变化
        PageTextElement dlg0 = row("TXT_A", PageTextElement.TYPE_DIALOGUE, 0, "旧文本");
        dlg0.setX(0.4);
        PageTextElement dlgStale = row("TXT_STALE", PageTextElement.TYPE_DIALOGUE, 5, "过期");
        PageTextElement narration = row("TXT_N", PageTextElement.TYPE_NARRATION, null, "旧旁白");
        when(elementMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(dlg0, dlgStale, narration));

        service.syncFromPageContent(10L);

        // idx0 文本更新、位置不变
        assertThat(dlg0.getSpeaker()).isEqualTo("林凡");
        assertThat(dlg0.getTextContent()).isEqualTo("这里是什么地方？");
        assertThat(dlg0.getX()).isEqualTo(0.4);
        // 过期对话被删
        verify(elementMapper).deleteBatchIds(List.of(dlgStale.getId()));
        // 旁白文本更新
        assertThat(narration.getTextContent()).isEqualTo("三日前,父亲的书房失窃。");
        // 同步版本标记(通过 updateById patch 落库)
        org.mockito.ArgumentCaptor<com.aimanga.v2.model.PageEntity> patchCaptor =
                org.mockito.ArgumentCaptor.forClass(com.aimanga.v2.model.PageEntity.class);
        verify(pageMapper).updateById(patchCaptor.capture());
        assertThat(patchCaptor.getValue().getTextLayoutVersion()).isEqualTo(2);
    }

    @Test
    void reset_clearsAndReinitializes() {
        when(elementMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        service.resetTextLayer(10L);
        verify(elementMapper).delete(any(LambdaQueryWrapper.class));
        verify(elementMapper, times(3)).insert(any(PageTextElement.class));
    }
}
