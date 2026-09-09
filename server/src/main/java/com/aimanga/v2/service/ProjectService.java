package com.aimanga.v2.service;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.dto.CreateProjectRequest;
import com.aimanga.v2.dto.PageResult;
import com.aimanga.v2.dto.ProjectVO;
import com.aimanga.v2.dto.UpdateProjectRequest;
import com.aimanga.v2.event.ProjectCreatedEvent;
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
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** 当前用户的作品分页列表(ADMIN 也只看自己的,全局监控在 /admin/tasks) */
    public PageResult<ProjectVO> listPaged(int page, int size, String keyword, Integer status) {
        Long userId = CurrentUser.id();
        String kw = (keyword == null || keyword.isBlank()) ? null : escapeLike(keyword.trim());
        long total = baseMapper.countByUserFiltered(userId, status, kw);
        List<ProjectVO> records = total == 0
                ? List.of()
                : baseMapper.selectPageByUserFiltered(userId, status, kw, (long) (page - 1) * size, size);
        return new PageResult<>(records, total);
    }

    /** LIKE 通配符转义,避免用户输入 %/_ 引发全表扫描语义 */
    private static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public Project create(String title, String sourceText, String aspectRatio, String colorMode, Long stylePresetId) {
        Project project = new Project();
        project.setUserId(CurrentUser.id());
        // 跨系统稳定 ID:创建时生成,此后任何操作(改标题/重拆话/重出图)都不得改变
        project.setContentUid(java.util.UUID.randomUUID().toString());
        project.setTitle(title);
        project.setSourceText(sourceText);
        project.setAspectRatio(normalizeAspect(aspectRatio, "3:4"));
        project.setColorMode(normalizeColorMode(colorMode, "partial"));
        project.setStylePresetId(stylePresetId);
        project.setStatus(Project.STATUS_PREPARING);
        project.setCategory("");
        project.setTags("[]");
        project.setSeriesStatus(Project.SERIES_COMPLETED);
        project.setCreateTime(LocalDateTime.now());
        save(project);
        // 发布创建事件 → 任务系统按 feature_auto_split 自动入队拆话
        eventPublisher.publishEvent(new ProjectCreatedEvent(project));
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
        ProjectVO vo = new ProjectVO();
        vo.setId(project.getId());
        vo.setContentUid(project.getContentUid());
        vo.setTitle(project.getTitle());
        vo.setStatus(project.getStatus());
        vo.setAspectRatio(project.getAspectRatio());
        vo.setColorMode(project.getColorMode());
        vo.setStylePresetId(project.getStylePresetId());
        vo.setTagline(project.getTagline());
        vo.setDescription(project.getDescription());
        vo.setCoverUrl(project.getCoverUrl());
        vo.setCategory(project.getCategory());
        vo.setTags(project.getTags());
        vo.setSeriesStatus(project.getSeriesStatus());
        vo.setSourceText(project.getSourceText());
        vo.setCreateTime(project.getCreateTime());
        vo.setUpdateTime(project.getUpdateTime());
        vo.setChapterCount(chapterCount == null ? 0 : chapterCount);
        vo.setPageCount(pageCount == null ? 0 : pageCount);
        return vo;
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
        if (request.description() != null) {
            patch.setDescription(request.description());
        }
        if (request.coverUrl() != null) {
            patch.setCoverUrl(request.coverUrl().isBlank() ? null : request.coverUrl().trim());
        }
        if (request.category() != null) {
            patch.setCategory(request.category().isBlank() ? "" : request.category().trim());
        }
        if (request.tags() != null) {
            patch.setTags(normalizeTags(request.tags()));
        }
        if (request.seriesStatus() != null) {
            if (request.seriesStatus() != Project.SERIES_ONGOING && request.seriesStatus() != Project.SERIES_COMPLETED) {
                throw new BusinessException(400, "连载状态只能为 1(连载中) 或 2(已完结)");
            }
            patch.setSeriesStatus(request.seriesStatus());
        }
        // 注意:content_uid 永不在此更新(请求 DTO 中即不含该字段)
        patch.setUpdateTime(LocalDateTime.now());
        updateById(patch);
        return getById(id);
    }

    /**
     * 标签规范化:空 → "[]";必须是合法 JSON 数组;其他结构视为非法。
     */
    static String normalizeTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return "[]";
        }
        String trimmed = raw.trim();
        com.fasterxml.jackson.databind.JsonNode node;
        try {
            node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed);
        } catch (Exception e) {
            throw new BusinessException(400, "标签格式不正确,应为 JSON 数组,如 [\"重生\",\"系统\"]");
        }
        if (!node.isArray()) {
            throw new BusinessException(400, "标签格式不正确,应为 JSON 数组,如 [\"重生\",\"系统\"]");
        }
        return trimmed;
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
