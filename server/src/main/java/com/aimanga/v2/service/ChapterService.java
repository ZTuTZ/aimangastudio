package com.aimanga.v2.service;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.dto.ChapterVO;
import com.aimanga.v2.dto.CreateChapterRequest;
import com.aimanga.v2.dto.UpdateChapterRequest;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.repository.ChapterMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChapterService extends ServiceImpl<ChapterMapper, Chapter> {

    private final ProjectService projectService;

    public List<ChapterVO> listByProject(Long projectId) {
        projectService.requireAccessible(projectId);
        return list(new LambdaQueryWrapper<Chapter>()
                        .eq(Chapter::getProjectId, projectId)
                        .orderByAsc(Chapter::getChapterNo))
                .stream().map(this::toVO).toList();
    }

    /** 手动新增话(话号顺延) */
    public Chapter create(Long projectId, CreateChapterRequest request) {
        Project project = projectService.requireAccessible(projectId);
        Integer max = list(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getProjectId, projectId)
                .orderByDesc(Chapter::getChapterNo)
                .last("limit 1"))
                .stream().findFirst().map(Chapter::getChapterNo).orElse(0);
        Chapter chapter = new Chapter();
        chapter.setProjectId(project.getId());
        chapter.setChapterNo(max + 1);
        chapter.setTitle(request.title() == null || request.title().isBlank()
                ? "第" + (max + 1) + "话" : request.title().trim());
        chapter.setScriptText(request.scriptText());
        chapter.setStatus(Chapter.STATUS_PENDING);
        chapter.setPageCount(0);
        chapter.setCreateTime(LocalDateTime.now());
        save(chapter);
        return chapter;
    }

    public Chapter requireAccessible(Long chapterId) {
        Chapter chapter = getById(chapterId);
        if (chapter == null) {
            throw new BusinessException(404, "话不存在: " + chapterId);
        }
        projectService.requireAccessible(chapter.getProjectId());
        return chapter;
    }

    public Chapter update(Long chapterId, UpdateChapterRequest request) {
        Chapter chapter = requireAccessible(chapterId);
        Chapter patch = new Chapter();
        patch.setId(chapterId);
        if (request.title() != null && !request.title().isBlank()) {
            patch.setTitle(request.title());
        }
        if (request.scriptText() != null) {
            patch.setScriptText(request.scriptText());
            // 手改原文后回退到待处理,提示可重新生成脚本(Phase 5 提供任务入口)
            patch.setStatus(Chapter.STATUS_PENDING);
        }
        patch.setUpdateTime(LocalDateTime.now());
        updateById(patch);
        return getById(chapterId);
    }

    public ChapterVO toVO(Chapter chapter) {
        return new ChapterVO(chapter.getId(), chapter.getProjectId(), chapter.getChapterNo(),
                chapter.getTitle(), chapter.getScriptText(), chapter.getStatus(),
                chapter.getPageCount(), chapter.getUpdateTime());
    }
}
