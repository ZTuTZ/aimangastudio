package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.repository.ChapterMapper;
import com.aimanga.v2.repository.PageMapper;
import com.aimanga.v2.repository.ProjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目/话完成状态重算服务(Phase 8.2 §4.9,修复 P0-3):
 * 局部生成(单话/多话 BATCH)成功不能把整部作品标 DONE ——
 * Project 完成状态必须由整部作品的全部话/页重新计算。
 *
 * 规则:
 * - Chapter:全部页 GEN_SUCCESS 且有图 → COMPLETE;任一页 FAILED → PARTIAL_FAILED;否则 GENERATING;
 * - Project:全部话 COMPLETE → DONE;任一话 PARTIAL → PARTIAL;否则 GENERATING。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectCompletionService {

    private final ChapterMapper chapterMapper;
    private final PageMapper pageMapper;
    private final ProjectMapper projectMapper;

    /** 重算单话状态 */
    public void recalculateChapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            return;
        }
        List<PageEntity> pages = pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                .eq(PageEntity::getChapterId, chapterId)
                .orderByAsc(PageEntity::getPageNo));
        if (pages.isEmpty()) {
            return; // 没有页的话不参与成品状态判定
        }
        long failed = pages.stream()
                .filter(p -> p.getGenerateStatus() != null && p.getGenerateStatus() == PageEntity.GEN_FAILED)
                .count();
        long success = pages.stream()
                .filter(p -> p.getGenerateStatus() != null && p.getGenerateStatus() == PageEntity.GEN_SUCCESS
                        && p.getGeneratedImageUrl() != null && !p.getGeneratedImageUrl().isBlank())
                .count();
        int status;
        if (failed > 0) {
            status = Chapter.STATUS_PARTIAL_FAILED;
        } else if (success == pages.size()) {
            status = Chapter.STATUS_COMPLETE;
        } else {
            status = Chapter.STATUS_GENERATING;
        }
        if (chapter.getStatus() == null || chapter.getStatus() != status) {
            Chapter patch = new Chapter();
            patch.setId(chapterId);
            patch.setStatus(status);
            patch.setUpdateTime(LocalDateTime.now());
            chapterMapper.updateById(patch);
            log.info("[completion] 话 {} 状态重算 → {}", chapterId, status);
        }
    }

    /** 重算整部作品状态:全部话 COMPLETE → DONE;任一 PARTIAL → PARTIAL;否则 GENERATING */
    public void recalculateProject(Long projectId) {
        List<Chapter> chapters = chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getProjectId, projectId));
        if (chapters.isEmpty()) {
            return;
        }
        boolean allComplete = true;
        boolean anyPartial = false;
        for (Chapter chapter : chapters) {
            Integer status = chapter.getStatus();
            if (status == null || status < Chapter.STATUS_SCRIPT_READY) {
                // 尚未进入成品阶段的.chapter 不阻塞其他话的完成判定,但作品不算 DONE
                allComplete = false;
                continue;
            }
            if (status == Chapter.STATUS_PARTIAL_FAILED) {
                anyPartial = true;
                allComplete = false;
            } else if (status != Chapter.STATUS_COMPLETE) {
                allComplete = false;
            }
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            return;
        }
        int status;
        if (allComplete) {
            status = Project.STATUS_DONE;
        } else if (anyPartial) {
            status = Project.STATUS_PARTIAL;
        } else {
            status = Project.STATUS_GENERATING;
        }
        if (project.getStatus() == null || project.getStatus() != status) {
            Project patch = new Project();
            patch.setId(projectId);
            patch.setStatus(status);
            patch.setUpdateTime(LocalDateTime.now());
            projectMapper.updateById(patch);
            log.info("[completion] 作品 {} 状态重算 → {}", projectId, status);
        }
    }
}
