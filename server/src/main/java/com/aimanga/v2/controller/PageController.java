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
    private final com.aimanga.v2.pipeline.GenerationRecordService generationRecordService;
    private final com.aimanga.v2.pipeline.TextLayerService textLayerService;

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

    /** 页生成记录(Phase 6.7:回溯/对比/排查) */
    @GetMapping("/pages/{id}/generation-records")
    public Result<List<com.aimanga.v2.model.GenerationRecord>> generationRecords(@PathVariable Long id) {
        pageService.requireAccessible(id);
        return Result.ok(generationRecordService.recordsOfPage(id));
    }

    // ---------- 文本层(Phase 7.8) ----------

    /** 获取文本层(空层表示未初始化) */
    @GetMapping("/pages/{id}/text-layer")
    public Result<com.aimanga.v2.dto.textlayer.TextLayerDto> getTextLayer(@PathVariable Long id) {
        pageService.requireAccessible(id);
        return Result.ok(textLayerService.getTextLayer(id));
    }

    /** 初始化:从 page.dialogue/narration 生成默认布局(幂等) */
    @PostMapping("/pages/{id}/text-layer/initialize")
    public Result<com.aimanga.v2.dto.textlayer.TextLayerDto> initializeTextLayer(@PathVariable Long id) {
        pageService.requireAccessible(id);
        return Result.ok(textLayerService.initializeFromPage(id));
    }

    /** 保存用户编辑(归一化坐标,按 uid 增删改) */
    @PutMapping("/pages/{id}/text-layer")
    public Result<com.aimanga.v2.dto.textlayer.TextLayerDto> saveTextLayer(
            @PathVariable Long id,
            @RequestBody com.aimanga.v2.dto.textlayer.TextLayerDto dto) {
        pageService.requireAccessible(id);
        return Result.ok(textLayerService.saveTextLayer(id, dto));
    }

    /** 重置:清空并按当前脚本重新生成默认布局 */
    @PostMapping("/pages/{id}/text-layer/reset")
    public Result<com.aimanga.v2.dto.textlayer.TextLayerDto> resetTextLayer(@PathVariable Long id) {
        pageService.requireAccessible(id);
        return Result.ok(textLayerService.resetTextLayer(id));
    }

    /** 同步脚本内容(更新 speaker/text,保留位置) */
    @PostMapping("/pages/{id}/text-layer/sync")
    public Result<com.aimanga.v2.dto.textlayer.TextLayerDto> syncTextLayer(@PathVariable Long id) {
        pageService.requireAccessible(id);
        return Result.ok(textLayerService.syncFromPageContent(id));
    }

    /** 单页上色(T6.6.1) */
    @PostMapping("/pages/{id}/colorize")
    public Result<com.aimanga.v2.dto.TaskVO> colorize(@PathVariable Long id,
                                                      @RequestBody(required = false) SinglePageRequest request) {
        return Result.ok(postProcess(id, "COLORIZE",
                request == null ? null : request.colorMode(), null, null));
    }

    /** 单页清晰化(T6.6.2) */
    @PostMapping("/pages/{id}/clean")
    public Result<com.aimanga.v2.dto.TaskVO> clean(@PathVariable Long id) {
        return Result.ok(postProcess(id, "CLEAN", null, null, null));
    }

    /** 单页局部重绘(T6.6.3):遮罩白=重绘区域 */
    @PostMapping("/pages/{id}/repaint")
    public Result<com.aimanga.v2.dto.TaskVO> repaint(@PathVariable Long id,
                                                     @RequestBody RepaintRequest request) {
        if (request.repaintPrompt() == null || request.repaintPrompt().isBlank()) {
            throw new com.aimanga.v2.common.BusinessException(400, "请填写重绘提示词");
        }
        if (request.maskUrl() == null || request.maskUrl().isBlank()) {
            throw new com.aimanga.v2.common.BusinessException(400, "请上传遮罩图(白色=重绘区域)");
        }
        return Result.ok(postProcess(id, "REPAINT", null, request.repaintPrompt(), request.maskUrl()));
    }

    private com.aimanga.v2.dto.TaskVO postProcess(Long pageId, String op, String colorMode, String repaintPrompt, String maskUrl) {
        PageEntity page = pageService.requireAccessible(pageId);
        stageService.createItems(page.getProjectId(), op, "PAGE", java.util.List.of(pageId));
        stageService.forceResetItemsByBusiness(page.getProjectId(), op, "PAGE", java.util.List.of(pageId));
        String payload;
        try {
            var mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder().build();
            var node = mapper.createObjectNode();
            node.put("pageId", pageId);
            if (colorMode != null && !colorMode.isBlank()) node.put("colorMode", colorMode);
            if (repaintPrompt != null) node.put("repaintPrompt", repaintPrompt);
            if (maskUrl != null) node.put("maskUrl", maskUrl);
            payload = mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new com.aimanga.v2.common.BusinessException(500, "构造任务参数失败");
        }
        return taskService.ensureUniqueActiveTask(page.getProjectId(), null, op, payload);
    }

    /** 单页生成请求体 */
    public record SinglePageRequest(String colorMode) {
    }

    /** 局部重绘请求体 */
    public record RepaintRequest(String repaintPrompt, String maskUrl) {
    }
}
