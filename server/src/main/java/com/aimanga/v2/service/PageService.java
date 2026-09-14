package com.aimanga.v2.service;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.dto.PageVO;
import com.aimanga.v2.dto.UpdatePageRequest;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.pipeline.PageAssetBindingService;
import com.aimanga.v2.repository.PageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PageService extends ServiceImpl<PageMapper, PageEntity> {

    private final ChapterService chapterService;
    private final PageAssetBindingService pageAssetBindingService;
    private final ObjectMapper objectMapper;

    public List<PageVO> listByChapter(Long chapterId) {
        chapterService.requireAccessible(chapterId);
        return list(new LambdaQueryWrapper<PageEntity>()
                        .eq(PageEntity::getChapterId, chapterId)
                        .orderByAsc(PageEntity::getPageNo))
                .stream().map(this::toVO).toList();
    }

    /** 新增页(页号顺延) */
    public PageEntity create(Long chapterId, UpdatePageRequest request) {
        Chapter chapter = chapterService.requireAccessible(chapterId);
        Integer max = list(new LambdaQueryWrapper<PageEntity>()
                .eq(PageEntity::getChapterId, chapterId)
                .orderByDesc(PageEntity::getPageNo)
                .last("limit 1"))
                .stream().findFirst().map(PageEntity::getPageNo).orElse(0);
        PageEntity page = new PageEntity();
        page.setProjectId(chapter.getProjectId());
        page.setChapterId(chapterId);
        page.setPageNo(max + 1);
        applyScript(page, request);
        page.setGenerateStatus(PageEntity.GEN_PENDING);
        page.setDialogue(orEmptyJson(page.getDialogue()));
        page.setGenerateRecords("[]");
        page.setCreateTime(LocalDateTime.now());
        save(page);
        return page;
    }

    public PageEntity requireAccessible(Long pageId) {
        PageEntity page = getById(pageId);
        if (page == null) {
            throw new BusinessException(404, "页面不存在: " + pageId);
        }
        chapterService.requireAccessible(page.getChapterId());
        return page;
    }

    public PageEntity update(Long pageId, UpdatePageRequest request) {
        PageEntity page = requireAccessible(pageId);
        PageEntity patch = new PageEntity();
        patch.setId(pageId);
        applyScript(patch, request);
        patch.setUpdateTime(LocalDateTime.now());
        updateById(patch);
        boolean textEdited = request != null
                && (request.narration() != null || request.dialogue() != null
                    || request.visual() != null || request.sceneDescription() != null);
        if (textEdited) {
            // T6.5.4:脚本文本修改 → 版本+1(布局/成品图据此判定过期,不删旧图)
            baseMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PageEntity>()
                    .eq(PageEntity::getId, pageId)
                    .setSql("script_version = script_version + 1"));
            // T6.5.5:保持页-素材绑定与新脚本一致
            pageAssetBindingService.rebuildPage(pageId);
        }
        return getById(pageId);
    }

    public void delete(Long pageId) {
        requireAccessible(pageId);
        removeById(pageId);
    }

    private void applyScript(PageEntity patch, UpdatePageRequest request) {
        if (request == null) {
            return;
        }
        if (request.narration() != null) patch.setNarration(request.narration());
        if (request.visual() != null) patch.setVisual(request.visual());
        if (request.sceneDescription() != null) patch.setSceneDescription(request.sceneDescription());
        if (request.dialogue() != null) {
            String dialogue = request.dialogue().isBlank() ? "[]" : request.dialogue().trim();
            try {
                objectMapper.readTree(dialogue);
            } catch (Exception e) {
                throw new BusinessException(400, "对白数据格式不正确,应为 [{speaker,line}] JSON");
            }
            patch.setDialogue(dialogue);
        }
    }

    private static String orEmptyJson(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }

    public PageVO toVO(PageEntity page) {
        return new PageVO(page.getId(), page.getChapterId(), page.getPageNo(),
                page.getNarration(), page.getDialogue(), page.getVisual(), page.getSceneDescription(),
                page.getLayoutImageUrl(), page.getGeneratedImageUrl(), page.getColorMode(),
                page.getGenerateStatus(), page.getFailReason(),
                page.getScriptVersion(), page.getLayoutScriptVersion(), page.getImageScriptVersion(),
                page.getGenerateRecords());
    }
}
