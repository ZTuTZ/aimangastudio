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
import com.aimanga.v2.pipeline.PipelineStageService;
import com.aimanga.v2.service.ImportService;
import com.aimanga.v2.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final ImportService importService;
    private final com.aimanga.v2.service.TaskService taskService;
    private final PipelineStageService stageService;

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
        stageService.pauseProject(id);
        return Result.ok();
    }

    /**
     * 继续项目(T5.11.5):恢复所有 PAUSED 阶段并逐阶段确保任务在跑。
     * 主准备流水线(SPLIT/ASSET/SCRIPT)按顺序;手动素材阶段(SHEET/REFERENCE)独立恢复,
     * 不能只靠 firstIncompleteStage 启动一个阶段(否则暂停的素材阶段会被遗漏)。
     */
    @PostMapping("/{id}/resume")
    public Result<Void> resume(@PathVariable Long id) {
        projectService.requireAccessible(id);
        List<String> pausedStages = stageService.pausedStageTypes(id);
        stageService.resumeProject(id);
        for (String stage : pausedStages) {
            // 阶段类型 → 任务类型映射(REFERENCE 阶段对应 ASSET_REF 任务)
            String taskType = "REFERENCE".equals(stage) ? "ASSET_REF" : stage;
            // enqueueUnique:已有活跃任务时静默跳过,不会产生第二个并行 Runner
            taskService.enqueueUnique(id, null, taskType, "{}");
        }
        return Result.ok();
    }

    /** 查询流水线各阶段进度 */
    @GetMapping("/{id}/pipeline")
    public Result<List<PipelineStage>> pipeline(@PathVariable Long id) {
        projectService.requireAccessible(id);
        return Result.ok(stageService.listByProject(id));
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
