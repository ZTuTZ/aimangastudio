package com.aimanga.v2.controller;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.common.Result;
import com.aimanga.v2.dto.CreateProjectRequest;
import com.aimanga.v2.dto.CreateTaskRequest;
import com.aimanga.v2.dto.PageResult;
import com.aimanga.v2.dto.ProjectVO;
import com.aimanga.v2.dto.TaskVO;
import com.aimanga.v2.dto.UpdateProjectRequest;
import com.aimanga.v2.model.PipelineStage;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.task.TaskStatus;
import com.aimanga.v2.pipeline.GenerationPreflightService;
import com.aimanga.v2.pipeline.PageAssetBindingService;
import com.aimanga.v2.pipeline.PipelineStageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aimanga.v2.service.ImportService;
import com.aimanga.v2.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final ImportService importService;
    private final com.aimanga.v2.service.TaskService taskService;
    private final PipelineStageService stageService;
    private final com.aimanga.v2.service.PipelineControlService pipelineControlService;
    private final GenerationPreflightService generationPreflightService;
    private final PageAssetBindingService pageAssetBindingService;
    private final com.aimanga.v2.pipeline.PublicationService publicationService;

    @GetMapping
    public Result<PageResult<ProjectVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        if (page < 1) {
            page = 1;
        }
        if (size < 1 || size > 50) {
            size = 12;
        }
        return Result.ok(projectService.listPaged(page, size, keyword, status));
    }

    @PostMapping
    public Result<ProjectVO> create(@Valid @RequestBody CreateProjectRequest request) {
        Project created = projectService.create(request.title(), request.sourceText(),
                request.aspectRatio(), request.colorMode(), request.stylePresetId(),
                request.sceneRatio(), request.propRatio(), request.costumeRatio());
        return Result.ok(projectService.toVO(created));
    }

    /** 批量导入:每个文件(TXT/DOCX)创建一部作品 */
    @PostMapping("/import")
    public Result<List<ProjectVO>> importProjects(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "aspectRatio", required = false, defaultValue = "3:4") String aspectRatio,
            @RequestParam(value = "colorMode", required = false, defaultValue = "partial") String colorMode,
            @RequestParam(value = "stylePresetId", required = false) Long stylePresetId,
            @RequestParam(value = "sceneRatio", required = false, defaultValue = "16:9") String sceneRatio,
            @RequestParam(value = "propRatio", required = false, defaultValue = "1:1") String propRatio,
            @RequestParam(value = "costumeRatio", required = false, defaultValue = "3:4") String costumeRatio) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(400, "请选择要导入的文件");
        }
        return Result.ok(importService.importFiles(files, aspectRatio, colorMode, stylePresetId,
                        sceneRatio, propRatio, costumeRatio)
                .stream().map(projectService::toVO).toList());
    }

    @GetMapping("/{id}")
    public Result<ProjectVO> get(@PathVariable Long id) {
        return Result.ok(projectService.toVO(projectService.requireAccessible(id)));
    }

    /** 手动触发拆话(处理器会拒绝已存在生成内容的作品) */
    @PostMapping("/{id}/split")
    public Result<TaskVO> split(@PathVariable Long id) {
        projectService.requireAccessible(id);
        return Result.ok(taskService.create(new CreateTaskRequest(id, null, "SPLIT", null)));
    }

    /** 手动重新提取资产 */
    @PostMapping("/{id}/rebuild-assets")
    public Result<TaskVO> rebuildAssets(@PathVariable Long id) {
        projectService.requireAccessible(id);
        return Result.ok(taskService.create(new CreateTaskRequest(id, null, "ASSET", null)));
    }

    /** 暂停项目的准备流水线(进行中阶段执行完当前步骤后暂停) */
    @PostMapping("/{id}/pause")
    public Result<Void> pause(@PathVariable Long id) {
        projectService.requireAccessible(id);
        pipelineControlService.pauseProject(id);
        return Result.ok();
    }

    /**
     * 继续项目(Phase 8.3 §5.5):恢复所有 PAUSED 阶段 + 恢复项目下 PAUSED 的原 Task。
     * 原 Task 携带原 payload(scope/colorMode/forceImage 等)原样继续,Task ID 不变;
     * 不再根据 Stage 创建空 payload 新 Task。
     */
    @PostMapping("/{id}/resume")
    public Result<Integer> resume(@PathVariable Long id) {
        projectService.requireAccessible(id);
        return Result.ok(pipelineControlService.resumeProject(id));
    }

    /** 查询流水线各阶段进度 */
    @GetMapping("/{id}/pipeline")
    public Result<List<PipelineStage>> pipeline(@PathVariable Long id) {
        projectService.requireAccessible(id);
        return Result.ok(stageService.listByProject(id));
    }

    /** 出图素材预检(T6.1.4):chapterId 为空 = 整部作品;chapterIds = 话集合(多话批量) */
    @GetMapping("/{id}/generation-preflight")
    public Result<com.aimanga.v2.pipeline.GenerationPreflightService.PreflightResult> generationPreflight(
            @PathVariable Long id,
            @RequestParam(required = false) Long chapterId,
            @RequestParam(required = false) List<Long> chapterIds) {
        projectService.requireAccessible(id);
        if (chapterIds != null && !chapterIds.isEmpty()) {
            return Result.ok(generationPreflightService.preflight(id, chapterIds));
        }
        return Result.ok(generationPreflightService.preflight(id, chapterId));
    }

    /** 重建页-素材绑定(T6.1.3 兼容旧脚本页:按现有对白/visual/场景描述程序匹配) */
    @PostMapping("/{id}/page-asset-refs/rebuild")
    public Result<Integer> rebuildPageAssetRefs(@PathVariable Long id) {
        projectService.requireAccessible(id);
        return Result.ok(pageAssetBindingService.rebuildForProject(id));
    }

    /**
     * 一键生成成品(BATCH,T6.3.4/6.3.5):
     * 1 个 BATCH Task → Preflight → LAYOUT Stage → IMAGE Stage → 汇总。
     * scope=CHAPTER 时按话去重,scope=PROJECT 时同项目只允许一个活跃 BATCH。
     */
    @PostMapping("/{id}/generate-batch")
    public Result<TaskVO> generateBatch(@PathVariable Long id,
                                        @RequestBody GenerateBatchRequest request) {
        projectService.requireAccessible(id);
        boolean scopeChapter = "CHAPTER".equals(request.scope());
        boolean scopeChapters = "CHAPTERS".equals(request.scope());
        Long chapterId = scopeChapter ? request.chapterId() : null;
        List<Long> chapterIds = scopeChapters ? request.chapterIds() : null;
        if (scopeChapter && chapterId == null) {
            throw new BusinessException(400, "按话生成必须提供 chapterId");
        }
        if (scopeChapters && (chapterIds == null || chapterIds.isEmpty())) {
            throw new BusinessException(400, "多话生成必须提供 chapterIds");
        }
        String payload;
        try {
            var mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder().build();
            var node = mapper.createObjectNode();
            node.put("scope", scopeChapter ? "CHAPTER" : scopeChapters ? "CHAPTERS" : "PROJECT");
            if (chapterId != null) node.put("chapterId", chapterId);
            if (scopeChapters) {
                var arr = node.putArray("chapterIds");
                chapterIds.forEach(arr::add);
            }
            node.put("colorMode", request.colorMode() == null ? "" : request.colorMode());
            node.put("skipGenerated", request.skipGenerated() == null || request.skipGenerated());
            node.put("forceLayout", Boolean.TRUE.equals(request.forceLayout()));
            node.put("forceImage", Boolean.TRUE.equals(request.forceImage()));
            payload = mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new BusinessException(500, "构造任务参数失败");
        }
        // 去重键:单话按话去重;整部/多话按项目去重(同项目只允许一个全量/批量 BATCH)
        return Result.ok(taskService.ensureUniqueActiveTask(id, chapterId, "BATCH", payload));
    }

    /** BATCH 生成请求体 */
    public record GenerateBatchRequest(
            String scope,
            Long chapterId,
            List<Long> chapterIds,
            String colorMode,
            Boolean skipGenerated,
            Boolean forceLayout,
            Boolean forceImage) {
    }

    @PutMapping("/{id}")
    public Result<ProjectVO> update(@PathVariable Long id, @RequestBody UpdateProjectRequest request) {
        return Result.ok(projectService.toVO(projectService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.requireAccessible(id);
        projectService.removeById(id); // 话/页/资产经 FK 级联删除
        return Result.ok();
    }
}
