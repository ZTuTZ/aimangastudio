package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.dto.textlayer.TextLayerDto;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.PageTextElement;
import com.aimanga.v2.repository.PageMapper;
import com.aimanga.v2.repository.PageTextElementMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 文本层服务(Phase 7.8 T7.8.3):底图与文本层解耦。
 * - initializeFromPage:首次进入从 page.dialogue/narration 生成默认布局(规则布局,不调 AI);
 * - saveTextLayer:保存用户编辑(按 element_uid 增删改,归一化坐标);
 * - syncFromPageContent:脚本修改后同步文本(更新 speaker/text,保留已有位置,新增/删除跟随对话数组);
 * - resetTextLayer:清空并按当前脚本重新生成默认布局。
 * 禁止:把文字烧进成品图、保存像素坐标、动 page.dialogue/narration 语义。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextLayerService {

    private static final double SAFE_MARGIN = 0.03;
    private static final double DIALOGUE_W = 0.30;
    private static final double DIALOGUE_H = 0.10;
    private static final double NARRATION_W = 0.88;
    private static final double NARRATION_H = 0.09;
    private static final double FONT_RATIO = 0.028;

    private final PageMapper pageMapper;
    private final PageTextElementMapper elementMapper;
    private final ObjectMapper objectMapper;

    /** 获取当前文本层(不自动创建,空层表示尚未初始化) */
    public TextLayerDto getTextLayer(Long pageId) {
        PageEntity page = requirePage(pageId);
        return buildDto(page, listElements(pageId));
    }

    /** 首次初始化:从 page.dialogue/narration 生成默认规则布局(幂等:已有元素直接返回) */
    public TextLayerDto initializeFromPage(Long pageId) {
        PageEntity page = requirePage(pageId);
        List<PageTextElement> existing = listElements(pageId);
        if (!existing.isEmpty()) {
            return buildDto(page, existing);
        }
        List<PageTextElement> created = new ArrayList<>();
        int sortOrder = 1;
        List<StoryScript.DialogueItem> dialogues = parseDialogue(page.getDialogue());
        for (int i = 0; i < dialogues.size(); i++) {
            StoryScript.DialogueItem d = dialogues.get(i);
            boolean left = i % 2 == 0;
            double x = left ? SAFE_MARGIN : 1 - SAFE_MARGIN - DIALOGUE_W;
            double y = SAFE_MARGIN + (i / 2) * 0.16;
            created.add(element(page, null, PageTextElement.TYPE_DIALOGUE, i,
                    safe(d.speaker()), safe(d.line()), x, y, DIALOGUE_W, DIALOGUE_H,
                    "DEFAULT_DIALOGUE", "DEFAULT_DIALOGUE", x + DIALOGUE_W / 2, y + DIALOGUE_H,
                    "CENTER", sortOrder++));
        }
        if (!isBlank(page.getNarration())) {
            created.add(element(page, null, PageTextElement.TYPE_NARRATION, null,
                    null, page.getNarration().trim(), SAFE_MARGIN, 1 - SAFE_MARGIN - NARRATION_H,
                    NARRATION_W, NARRATION_H, "NARRATION_BOX", "DEFAULT_NARRATION", null, null,
                    "LEFT", sortOrder++));
        }
        created.forEach(elementMapper::insert);
        markSynced(page);
        log.info("[text-layer] 页 {} 初始化文本层: {} 个元素", pageId, created.size());
        return buildDto(page, listElements(pageId));
    }

    /** 保存用户编辑:按 element_uid 增删改(新元素 sourceType=MANUAL),坐标钳制到 0~1 */
    public TextLayerDto saveTextLayer(Long pageId, TextLayerDto dto) {
        PageEntity page = requirePage(pageId);
        if (dto == null || dto.elements() == null) {
            throw new BusinessException(400, "文本层数据缺失 elements");
        }
        List<PageTextElement> existing = listElements(pageId);
        Set<String> keptUids = new HashSet<>();
        int sortOrder = 1;
        for (TextLayerDto.Element e : dto.elements()) {
            if (e == null || e.uid() == null || e.uid().isBlank()) {
                throw new BusinessException(400, "存在缺少 uid 的文本元素");
            }
            if (!keptUids.add(e.uid())) {
                throw new BusinessException(400, "重复的元素 uid: " + e.uid());
            }
            validateType(e.type());
            PageTextElement row = existing.stream()
                    .filter(r -> r.getElementUid().equals(e.uid())).findFirst().orElse(null);
            boolean isNew = row == null;
            if (isNew) {
                row = new PageTextElement();
                row.setPageId(pageId);
                row.setProjectId(page.getProjectId());
                row.setChapterId(page.getChapterId());
                row.setElementUid(e.uid());
                row.setSourceType(PageTextElement.SOURCE_MANUAL);
                row.setVersion(1);
                row.setCreateTime(LocalDateTime.now());
            }
            row.setElementType(e.type());
            row.setDialogueIndex(e.dialogueIndex());
            row.setSpeaker(e.speaker());
            row.setTextContent(e.text() == null ? "" : e.text());
            TextLayerDto.Position pos = e.position();
            if (pos == null) {
                throw new BusinessException(400, "元素 " + e.uid() + " 缺少 position");
            }
            row.setX(clamp(pos.x()));
            row.setY(clamp(pos.y()));
            row.setWidth(clamp(pos.width()));
            row.setHeight(pos.height() == null ? null : clamp(pos.height()));
            TextLayerDto.Bubble bubble = e.bubble();
            row.setBubbleStyle(bubble == null || isBlank(bubble.preset()) ? "DEFAULT_DIALOGUE" : bubble.preset());
            TextLayerDto.Tail tail = bubble == null ? null : bubble.tail();
            row.setTailX(tail == null || tail.x() == null ? null : clamp(tail.x()));
            row.setTailY(tail == null || tail.y() == null ? null : clamp(tail.y()));
            TextLayerDto.Style style = e.style();
            row.setFontStyle(style == null || isBlank(style.fontPreset())
                    ? defaultFontOf(e.type()) : style.fontPreset());
            row.setFontSizeRatio(style == null || style.fontSizeRatio() == null ? FONT_RATIO : clamp(style.fontSizeRatio()));
            row.setTextAlign(style == null || isBlank(style.align()) ? "CENTER" : style.align());
            row.setMaxLines(style == null ? null : style.maxLines());
            row.setSortOrder(e.sortOrder() == null ? sortOrder : e.sortOrder());
            row.setUpdateTime(LocalDateTime.now());
            if (isNew) {
                elementMapper.insert(row);
            } else {
                elementMapper.updateById(row);
            }
            sortOrder++;
        }
        // 删除请求中不存在的元素
        List<Long> removedIds = existing.stream()
                .filter(r -> !keptUids.contains(r.getElementUid()))
                .map(PageTextElement::getId).toList();
        if (!removedIds.isEmpty()) {
            elementMapper.deleteBatchIds(removedIds);
        }
        return buildDto(page, listElements(pageId));
    }

    /** 重置:清空后按当前脚本重新生成默认布局 */
    public TextLayerDto resetTextLayer(Long pageId) {
        requirePage(pageId);
        elementMapper.delete(new LambdaQueryWrapper<PageTextElement>()
                .eq(PageTextElement::getPageId, pageId));
        return initializeFromPage(pageId);
    }

    /** 脚本同步(T7.8.16):更新 speaker/text,保留位置;新增对话创建元素,删除对话移除元素 */
    public TextLayerDto syncFromPageContent(Long pageId) {
        PageEntity page = requirePage(pageId);
        List<PageTextElement> elements = listElements(pageId);
        List<StoryScript.DialogueItem> dialogues = parseDialogue(page.getDialogue());

        int created = 0;
        int updated = 0;
        // 对白按 dialogueIndex 对齐
        for (int i = 0; i < dialogues.size(); i++) {
            final int idx = i;
            StoryScript.DialogueItem d = dialogues.get(i);
            PageTextElement row = elements.stream()
                    .filter(e -> PageTextElement.TYPE_DIALOGUE.equals(e.getElementType())
                            && e.getDialogueIndex() != null && e.getDialogueIndex() == idx)
                    .findFirst().orElse(null);
            if (row != null) {
                if (!safe(d.speaker()).equals(row.getSpeaker()) || !safe(d.line()).equals(row.getTextContent())) {
                    row.setSpeaker(safe(d.speaker()));
                    row.setTextContent(safe(d.line()));
                    row.setUpdateTime(LocalDateTime.now());
                    elementMapper.updateById(row);
                    updated++;
                }
            } else {
                boolean left = i % 2 == 0;
                double x = left ? SAFE_MARGIN : 1 - SAFE_MARGIN - DIALOGUE_W;
                double y = SAFE_MARGIN + (i / 2) * 0.16;
                PageTextElement fresh = element(page, null, PageTextElement.TYPE_DIALOGUE, i,
                        safe(d.speaker()), safe(d.line()), x, y, DIALOGUE_W, DIALOGUE_H,
                        "DEFAULT_DIALOGUE", "DEFAULT_DIALOGUE", x + DIALOGUE_W / 2, y + DIALOGUE_H,
                        "CENTER", elements.size() + created + 1);
                elementMapper.insert(fresh);
                created++;
            }
        }
        // 超出对话数量的旧元素移除
        List<Long> staleDialogue = elements.stream()
                .filter(e -> PageTextElement.TYPE_DIALOGUE.equals(e.getElementType())
                        && e.getDialogueIndex() != null && e.getDialogueIndex() >= dialogues.size())
                .map(PageTextElement::getId).toList();
        if (!staleDialogue.isEmpty()) {
            elementMapper.deleteBatchIds(staleDialogue);
        }
        // 旁白:更新文本;无旁白则移除
        PageTextElement narrationRow = elements.stream()
                .filter(e -> PageTextElement.TYPE_NARRATION.equals(e.getElementType())).findFirst().orElse(null);
        if (!isBlank(page.getNarration())) {
            if (narrationRow != null) {
                if (!page.getNarration().trim().equals(narrationRow.getTextContent())) {
                    narrationRow.setTextContent(page.getNarration().trim());
                    narrationRow.setUpdateTime(LocalDateTime.now());
                    elementMapper.updateById(narrationRow);
                    updated++;
                }
            } else {
                PageTextElement fresh = element(page, null, PageTextElement.TYPE_NARRATION, null,
                        null, page.getNarration().trim(), SAFE_MARGIN, 1 - SAFE_MARGIN - NARRATION_H,
                        NARRATION_W, NARRATION_H, "NARRATION_BOX", "DEFAULT_NARRATION", null, null,
                        "LEFT", elements.size() + created + 1);
                elementMapper.insert(fresh);
                created++;
            }
        } else if (narrationRow != null) {
            elementMapper.deleteById(narrationRow.getId());
        }
        markSynced(page);
        log.info("[text-layer] 页 {} 同步脚本内容: 更新 {} 新增 {}", pageId, updated, created);
        return buildDto(page, listElements(pageId));
    }

    /** 导出用:按页取全部元素(Comic Package 兼容,T7.8.9) */
    public List<PageTextElement> listByProject(Long projectId) {
        return elementMapper.selectList(new LambdaQueryWrapper<PageTextElement>()
                .eq(PageTextElement::getProjectId, projectId)
                .orderByAsc(PageTextElement::getPageId)
                .orderByAsc(PageTextElement::getSortOrder));
    }

    // ---------- 内部 ----------

    private PageTextElement element(PageEntity page, Long ignore, String type, Integer dialogueIndex,
                                    String speaker, String text, double x, double y, double w, Double h,
                                    String bubbleStyle, String fontPreset, Double tailX, Double tailY,
                                    String align, int sortOrder) {
        PageTextElement row = new PageTextElement();
        row.setProjectId(page.getProjectId());
        row.setChapterId(page.getChapterId());
        row.setPageId(page.getId());
        row.setElementUid("TXT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        row.setElementType(type);
        row.setDialogueIndex(dialogueIndex);
        row.setSpeaker(isBlank(speaker) ? null : speaker);
        row.setTextContent(text == null ? "" : text);
        row.setX(x);
        row.setY(y);
        row.setWidth(w);
        row.setHeight(h);
        row.setTailX(tailX);
        row.setTailY(tailY);
        row.setBubbleStyle(bubbleStyle);
        row.setFontStyle(fontPreset);
        row.setFontSizeRatio(FONT_RATIO);
        row.setTextAlign(align);
        row.setMaxLines(4);
        row.setSortOrder(sortOrder);
        row.setSourceType(PageTextElement.SOURCE_AUTO);
        row.setVersion(1);
        row.setCreateTime(LocalDateTime.now());
        return row;
    }

    private TextLayerDto buildDto(PageEntity page, List<PageTextElement> rows) {
        List<TextLayerDto.Element> elements = rows.stream().map(r -> new TextLayerDto.Element(
                r.getElementUid(),
                r.getElementType(),
                r.getDialogueIndex(),
                r.getSpeaker(),
                r.getTextContent(),
                new TextLayerDto.Position(orZero(r.getX()), orZero(r.getY()), orZero(r.getWidth()),
                        r.getHeight() == null ? null : r.getHeight()),
                new TextLayerDto.Style(r.getFontStyle(), r.getFontSizeRatio(), r.getTextAlign(), r.getMaxLines()),
                new TextLayerDto.Bubble(r.getBubbleStyle(),
                        r.getTailX() == null || r.getTailY() == null ? null
                                : new TextLayerDto.Tail(r.getTailX(), r.getTailY())),
                r.getSortOrder())).toList();
        return new TextLayerDto(TextLayerDto.SCHEMA_VERSION, page.getId(),
                page.getScriptVersion(), page.getTextLayoutVersion(), elements);
    }

    private void markSynced(PageEntity page) {
        int scriptVersion = page.getScriptVersion() == null ? 1 : page.getScriptVersion();
        PageEntity patch = new PageEntity();
        patch.setId(page.getId());
        patch.setTextLayoutVersion(scriptVersion);
        pageMapper.updateById(patch);
    }

    private PageEntity requirePage(Long pageId) {
        PageEntity page = pageMapper.selectById(pageId);
        if (page == null) {
            throw new BusinessException(404, "页面不存在: " + pageId);
        }
        return page;
    }

    private List<PageTextElement> listElements(Long pageId) {
        return elementMapper.selectList(new LambdaQueryWrapper<PageTextElement>()
                .eq(PageTextElement::getPageId, pageId)
                .orderByAsc(PageTextElement::getSortOrder)
                .orderByAsc(PageTextElement::getId));
    }

    private List<StoryScript.DialogueItem> parseDialogue(String dialogueJson) {
        try {
            JsonNode node = objectMapper.readTree(isBlank(dialogueJson) ? "[]" : dialogueJson);
            List<StoryScript.DialogueItem> list = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(n -> list.add(new StoryScript.DialogueItem(
                        n.path("speaker").asText(""), n.path("line").asText(""))));
            }
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void validateType(String type) {
        if (!PageTextElement.TYPE_DIALOGUE.equals(type) && !PageTextElement.TYPE_NARRATION.equals(type)
                && !PageTextElement.TYPE_THOUGHT.equals(type) && !PageTextElement.TYPE_SFX.equals(type)) {
            throw new BusinessException(400, "未知文本元素类型: " + type);
        }
    }

    private String defaultFontOf(String type) {
        return PageTextElement.TYPE_NARRATION.equals(type) ? "DEFAULT_NARRATION" : "DEFAULT_DIALOGUE";
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double orZero(Double v) {
        return v == null ? 0.0 : v;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
