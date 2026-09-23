package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.StylePreset;
import com.aimanga.v2.repository.AssetMapper;
import com.aimanga.v2.repository.ChapterMapper;
import com.aimanga.v2.repository.PageMapper;
import com.aimanga.v2.repository.ProjectMapper;
import com.aimanga.v2.repository.StylePresetMapper;
import com.aimanga.v2.repository.TaskMapper;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.service.TaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 流水线共享上下文:数据访问、风格提示词、特性开关、链式入队。
 * 注意:处理器运行在 worker 线程(无 Shiro 上下文),一切数据访问走 Mapper,不走带归属校验的 Service。
 */
@Component
@RequiredArgsConstructor
public class PipelineContext {

    public final ProjectMapper projectMapper;
    public final ChapterMapper chapterMapper;
    public final PageMapper pageMapper;
    public final AssetMapper assetMapper;
    public final StylePresetMapper stylePresetMapper;
    public final TaskMapper taskMapper;
    public final ConfigService configService;
    private final TaskService taskService;

    public Project project(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new IllegalArgumentException("作品不存在: " + id);
        }
        return project;
    }

    public List<Chapter> chaptersOf(Long projectId) {
        return chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getProjectId, projectId)
                .orderByAsc(Chapter::getChapterNo));
    }

    public long pageCountOf(Long chapterId) {
        return pageMapper.selectCount(new LambdaQueryWrapper<com.aimanga.v2.model.PageEntity>()
                .eq(com.aimanga.v2.model.PageEntity::getChapterId, chapterId));
    }

    /** 作品风格提示词(风格预设 positive_prompt;未设置返回空串) */
    public String stylePromptOf(Project project) {
        if (project.getStylePresetId() == null) {
            return "";
        }
        StylePreset preset = stylePresetMapper.selectById(project.getStylePresetId());
        return preset == null || preset.getPositivePrompt() == null ? "" : preset.getPositivePrompt();
    }

    /** 本话脚本原文:话内原文为空时回退作品原文 */
    public String chapterText(Project project, Chapter chapter) {
        if (chapter.getScriptText() != null && !chapter.getScriptText().isBlank()) {
            return chapter.getScriptText();
        }
        return safe(project.getSourceText());
    }

    public boolean feature(String key) {
        return configService.getInt(key, 1) == 1;
    }

    /** 链式入队(系统任务,不经过用户归属校验,userId 取作品归属) */
    public void enqueue(Long projectId, Long chapterId, String type, String payloadJson) {
        try {
            taskService.createSystemTask(projectId, chapterId, type, payloadJson);
        } catch (Exception e) {
            // 链式入队失败不影响当前任务成功状态,人工可在任务中心手动补
            org.slf4j.LoggerFactory.getLogger(PipelineContext.class)
                    .error("[pipeline] 链式入队失败 type={} projectId={}: {}", type, projectId, e.getMessage());
        }
    }

    /** 链式去重入队:同一 (projectId, chapterId, type) 在 PENDING/RUNNING 时只保留一个,防并发竞态 */
    public boolean enqueueUnique(Long projectId, Long chapterId, String type, String payloadJson) {
        try {
            return taskService.enqueueUnique(projectId, chapterId, type, payloadJson);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(PipelineContext.class)
                    .error("[pipeline] 去重入队失败 type={} projectId={}: {}", type, projectId, e.getMessage());
            return false;
        }
    }

    public static String safe(String s) {
        return s == null ? "" : s;
    }
}
