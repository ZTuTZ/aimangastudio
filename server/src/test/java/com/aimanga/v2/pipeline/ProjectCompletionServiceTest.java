package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.repository.ChapterMapper;
import com.aimanga.v2.repository.PageMapper;
import com.aimanga.v2.repository.ProjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 8.2 §4.9 项目完成状态重算单测:
 * 局部话成功不能把整部作品标 DONE(修复 P0-3)。
 */
class ProjectCompletionServiceTest {

    private ChapterMapper chapterMapper;
    private PageMapper pageMapper;
    private ProjectMapper projectMapper;
    private ProjectCompletionService service;

    @BeforeEach
    void setUp() {
        chapterMapper = mock(ChapterMapper.class);
        pageMapper = mock(PageMapper.class);
        projectMapper = mock(ProjectMapper.class);
        service = new ProjectCompletionService(chapterMapper, pageMapper, projectMapper);
    }

    private Chapter chapter(long id, int status) {
        Chapter c = new Chapter();
        c.setId(id);
        c.setProjectId(100L);
        c.setChapterNo((int) id);
        c.setStatus(status);
        return c;
    }

    private PageEntity page(long id, long chapterId, int status, String url) {
        PageEntity p = new PageEntity();
        p.setId(id);
        p.setChapterId(chapterId);
        p.setPageNo((int) id);
        p.setGenerateStatus(status);
        p.setGeneratedImageUrl(url);
        return p;
    }

    @Test
    void chapter_complete_whenAllPagesSuccess() {
        when(pageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                page(1, 5, PageEntity.GEN_SUCCESS, "https://oss/1.jpg"),
                page(2, 5, PageEntity.GEN_SUCCESS, "https://oss/2.jpg")));

        when(chapterMapper.selectById(5L)).thenReturn(chapter(5, Chapter.STATUS_GENERATING));
        service.recalculateChapter(5L);

        ArgumentCaptor<Chapter> captor = ArgumentCaptor.forClass(Chapter.class);
        verify(chapterMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Chapter.STATUS_COMPLETE);
    }

    @Test
    void chapter_partial_whenAnyPageFailed() {
        when(pageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                page(1, 5, PageEntity.GEN_SUCCESS, "https://oss/1.jpg"),
                page(2, 5, PageEntity.GEN_FAILED, null)));

        when(chapterMapper.selectById(5L)).thenReturn(chapter(5, Chapter.STATUS_GENERATING));
        service.recalculateChapter(5L);

        ArgumentCaptor<Chapter> captor = ArgumentCaptor.forClass(Chapter.class);
        verify(chapterMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Chapter.STATUS_PARTIAL_FAILED);
    }

    @Test
    void project_notDone_whenOtherChaptersNotGenerated() {
        // 20话作品只生成第3话:第3话 COMPLETE,其他话 SCRIPT_READY —— 项目不能 DONE(修复 P0-3)
        when(chapterMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                chapter(1, Chapter.STATUS_SCRIPT_READY),
                chapter(2, Chapter.STATUS_SCRIPT_READY),
                chapter(3, Chapter.STATUS_COMPLETE)));
        when(projectMapper.selectById(100L)).thenReturn(projectWith(100L, Project.STATUS_GENERATING));

        service.recalculateProject(100L);

        // 项目不能标 DONE:已是 GENERATING 且重算仍为 GENERATING → 不写终态
        org.mockito.Mockito.verify(projectMapper, org.mockito.Mockito.never())
                .updateById(org.mockito.ArgumentMatchers.any(Project.class));
    }

    @Test
    void project_done_whenAllChaptersComplete() {
        when(chapterMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                chapter(1, Chapter.STATUS_COMPLETE),
                chapter(2, Chapter.STATUS_COMPLETE)));
        when(projectMapper.selectById(100L)).thenReturn(projectWith(100L, Project.STATUS_GENERATING));

        service.recalculateProject(100L);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Project.STATUS_DONE);
    }

    @Test
    void project_partial_whenAnyChapterPartial() {
        when(chapterMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                chapter(1, Chapter.STATUS_COMPLETE),
                chapter(2, Chapter.STATUS_PARTIAL_FAILED)));
        when(projectMapper.selectById(100L)).thenReturn(projectWith(100L, Project.STATUS_GENERATING));

        service.recalculateProject(100L);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Project.STATUS_PARTIAL);
    }

    private Project projectWith(long id, int status) {
        Project p = new Project();
        p.setId(id);
        p.setStatus(status);
        return p;
    }
}
