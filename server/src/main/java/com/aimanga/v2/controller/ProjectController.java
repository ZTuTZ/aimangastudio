package com.aimanga.v2.controller;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.common.Result;
import com.aimanga.v2.dto.CreateProjectRequest;
import com.aimanga.v2.dto.PageResult;
import com.aimanga.v2.dto.ProjectVO;
import com.aimanga.v2.dto.UpdateProjectRequest;
import com.aimanga.v2.model.Project;
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
                request.aspectRatio(), request.colorMode(), request.stylePresetId());
        return Result.ok(projectService.toVO(created));
    }

    /** 批量导入:每个文件(TXT/DOCX)创建一部作品 */
    @PostMapping("/import")
    public Result<List<ProjectVO>> importProjects(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "aspectRatio", required = false, defaultValue = "3:4") String aspectRatio,
            @RequestParam(value = "colorMode", required = false, defaultValue = "partial") String colorMode,
            @RequestParam(value = "stylePresetId", required = false) Long stylePresetId) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(400, "请选择要导入的文件");
        }
        return Result.ok(importService.importFiles(files, aspectRatio, colorMode, stylePresetId)
                .stream().map(projectService::toVO).toList());
    }

    @GetMapping("/{id}")
    public Result<ProjectVO> get(@PathVariable Long id) {
        return Result.ok(projectService.toVO(projectService.requireAccessible(id)));
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
