package com.aimanga.v2.service;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.dto.CreateProjectRequest;
import com.aimanga.v2.dto.ProjectVO;
import com.aimanga.v2.dto.UpdateProjectRequest;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.repository.ChapterMapper;
import com.aimanga.v2.repository.PageMapper;
import com.aimanga.v2.repository.ProjectMapper;
import com.aimanga.v2.security.CurrentUser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService extends ServiceImpl<ProjectMapper, Project> {

    private final ChapterMapper chapterMapper;
    private final PageMapper pageMapper;

    /** 当前用户的作品列表(ADMIN 也只看自己的,全局监控在 /admin/tasks) */
    public List<ProjectVO> listMine() {
        return list(new LambdaQueryWrapper<Project>()
                        .eq(Project::getUserId, CurrentUser.id())
                        .orderByDesc(Project::getId))
                .stream().map(this::toVO).toList();
    }

    public Project create(String title, String sourceText, String aspectRatio, String colorMode, Long stylePresetId) {
        Project project = new Project();
        project.setUserId(CurrentUser.id());
        project.setTitle(title);
        project.setSourceText(sourceText);
        project.setAspectRatio(normalizeAspect(aspectRatio, "3:4"));
        project.setColorMode(normalizeColorMode(colorMode, "partial"));
        project.setStylePresetId(stylePresetId);
        project.setStatus(Project.STATUS_PREPARING);
        project.setCreateTime(LocalDateTime.now());
        save(project);
        // Phase 5:此处按 feature_auto_split 开关入队 SPLIT 拆话任务
        return project;
    }

    /** 归属校验:非本人且非 ADMIN 一律 404(避免探测他人作品存在性) */
    public Project requireAccessible(Long projectId) {
        Project project = getById(projectId);
        if (project == null) {
            throw new BusinessException(404, "作品不存在: " + projectId);
        }
        if (!CurrentUser.isAdmin() && !project.getUserId().equals(CurrentUser.id())) {
            throw new BusinessException(404, "作品不存在: " + projectId);
        }
        return project;
    }

    public ProjectVO toVO(Project project) {
        Long chapterCount = chapterMapper.selectCount(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getProjectId, project.getId()));
        Long pageCount = pageMapper.selectCount(new LambdaQueryWrapper<PageEntity>()
                .eq(PageEntity::getProjectId, project.getId()));
        return new ProjectVO(project.getId(), project.getTitle(), project.getStatus(),
                project.getAspectRatio(), project.getColorMode(), project.getStylePresetId(),
                project.getTagline(), project.getSourceText(), project.getCreateTime(), project.getUpdateTime(),
                chapterCount == null ? 0 : chapterCount, pageCount == null ? 0 : pageCount);
    }

    public Project update(Long id, UpdateProjectRequest request) {
        Project project = requireAccessible(id);
        Project patch = new Project();
        patch.setId(id);
        if (request.title() != null && !request.title().isBlank()) {
            patch.setTitle(request.title());
        }
        if (request.aspectRatio() != null && !request.aspectRatio().isBlank()) {
            patch.setAspectRatio(normalizeAspect(request.aspectRatio(), project.getAspectRatio()));
        }
        if (request.colorMode() != null && !request.colorMode().isBlank()) {
            patch.setColorMode(normalizeColorMode(request.colorMode(), project.getColorMode()));
        }
        if (request.stylePresetId() != null) {
            patch.setStylePresetId(request.stylePresetId());
        }
        if (request.tagline() != null) {
            patch.setTagline(request.tagline());
        }
        patch.setUpdateTime(LocalDateTime.now());
        updateById(patch);
        return getById(id);
    }

    private static String normalizeAspect(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value) {
            case "3:4", "2:3", "1:1", "16:9", "A4" -> value;
            case "竖版" -> "3:4";
            case "正方形" -> "1:1";
            case "横版" -> "16:9";
            default -> fallback;
        };
    }

    private static String normalizeColorMode(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value) {
            case "partial", "monochrome", "color" -> value;
            default -> fallback;
        };
    }
}
