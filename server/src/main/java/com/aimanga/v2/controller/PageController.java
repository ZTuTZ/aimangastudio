package com.aimanga.v2.controller;

import com.aimanga.v2.common.Result;
import com.aimanga.v2.dto.PageVO;
import com.aimanga.v2.dto.UpdatePageRequest;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.pipeline.PipelineStageService;
import com.aimanga.v2.service.PageService;
import com.aimanga.v2.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PageController {

    private final PageService pageService;
    private final TaskService taskService;
    private final PipelineStageService stageService;

    @GetMapping("/pages/{id}")
    public Result<PageVO> get(@PathVariable Long id) {
        return Result.ok(pageService.toVO(pageService.requireAccessible(id)));
    }

    @GetMapping("/chapters/{chapterId}/pages")
    public Result<List<PageVO>> listByChapter(@PathVariable Long chapterId) {
        return Result.ok(pageService.listByChapter(chapterId));
    }

    @PostMapping("/chapters/{chapterId}/pages")
    public Result<PageVO> create(@PathVariable Long chapterId, @RequestBody(required = false) UpdatePageRequest request) {
        return Result.ok(pageService.toVO(pageService.create(chapterId, request == null ? new UpdatePageRequest(null, null, null, null) : request)));
    }

    @PutMapping("/pages/{id}")
    public Result<PageVO> update(@PathVariable Long id, @RequestBody UpdatePageRequest request) {
        return Result.ok(pageService.toVO(pageService.update(id, request)));
    }

    @DeleteMapping("/pages/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        pageService.delete(id);
        return Result.ok();
    }

    /** 单页重生成布局(T6.5.2):forceReset 该页 LAYOUT Item,复用 LayoutGenerationService,不复制 BATCH 逻辑 */
    @PostMapping("/pages/{id}/generate-layout")
    public Result<com.aimanga.v2.dto.TaskVO> generateLayout(@PathVariable Long id) {
        PageEntity page = pageService.requireAccessible(id);
        stageService.createItems(page.getProjectId(), PipelineStageService.STAGE_LAYOUT, "PAGE", java.util.List.of(id));
        stageService.forceResetItemsByBusiness(page.getProjectId(), PipelineStageService.STAGE_LAYOUT,
                "PAGE", java.util.List.of(id));
        // 同 project 的 LAYOUT 任务去重:已有活跃任务时复用(Runner 自动领取新增/重置的 Item)
        return Result.ok(taskService.ensureUniqueActiveTask(page.getProjectId(), null, "LAYOUT",
                "{\"pageId\":" + id + "}"));
    }

    /** 单页重生成成品(T6.5.3):forceReset 该页 IMAGE Item,复用 PageGenerationService */
    @PostMapping("/pages/{id}/generate")
    public Result<com.aimanga.v2.dto.TaskVO> generatePage(@PathVariable Long id,
                                                          @RequestBody(required = false) SinglePageRequest request) {
        PageEntity page = pageService.requireAccessible(id);
        stageService.createItems(page.getProjectId(), PipelineStageService.STAGE_IMAGE, "PAGE", java.util.List.of(id));
        stageService.forceResetItemsByBusiness(page.getProjectId(), PipelineStageService.STAGE_IMAGE,
                "PAGE", java.util.List.of(id));
        String colorMode = request == null || request.colorMode() == null || request.colorMode().isBlank()
                ? "" : request.colorMode();
        return Result.ok(taskService.ensureUniqueActiveTask(page.getProjectId(), page.getChapterId(), "PAGE",
                "{\"pageId\":" + id + ",\"colorMode\":\"" + colorMode + "\"}"));
    }

    /** 单页生成请求体 */
    public record SinglePageRequest(String colorMode) {
    }
}
