package com.aimanga.v2.controller;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.common.Result;
import com.aimanga.v2.pipeline.PublicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量导出(Phase 7.5,仅 ADMIN):
 * 选中 N 部作品 → 批量校验 → 逐部导出 → 打成一个总 ZIP(内含每部 comic-{uid}.zip 与 summary.json)。
 * 失败作品不阻塞其他作品,结果在 summary 中逐部列明,不能要求人工逐部下载。
 */
@RestController
@RequestMapping("/api/admin/export")
@RequiredArgsConstructor
public class AdminExportController {

    private final PublicationService publicationService;
    private final com.aimanga.v2.service.TaskService taskService;
    private final com.aimanga.v2.pipeline.ExportArtifactService artifactService;

    /** 批量校验:返回每部作品的校验报告(不打包) */
    @PostMapping("/batch-validate")
    public Result<Map<String, Object>> batchValidate(@RequestBody BatchExportRequest request) {
        requireIds(request);
        List<Map<String, Object>> items = new ArrayList<>();
        int success = 0;
        int failed = 0;
        for (Long projectId : request.projectIds()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("projectId", projectId);
            try {
                PublicationService.PublicationReport report = publicationService.validate(projectId);
                item.put("valid", report.valid());
                item.put("chapterCount", report.chapterCount());
                item.put("pageCount", report.pageCount());
                item.put("issues", report.issues());
                if (report.valid()) success++; else failed++;
            } catch (Exception e) {
                item.put("valid", false);
                item.put("issues", List.of(Map.of("level", "ERROR", "message", e.getMessage())));
                failed++;
            }
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", request.projectIds().size());
        result.put("success", success);
        result.put("failed", failed);
        result.put("items", items);
        return Result.ok(result);
    }

    /** 批量导出:创建后台 EXPORT 任务，完成后从 task.result 获取下载地址与逐本错误。 */
    @PostMapping("/batch")
    public Result<com.aimanga.v2.dto.TaskVO> batchExport(@RequestBody BatchExportRequest request) {
        requireIds(request);
        try {
            List<Long> ids = new ArrayList<>(new java.util.LinkedHashSet<>(request.projectIds()));
            var payload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            payload.set("projectIds", new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(ids));
            return Result.ok(taskService.create(new com.aimanga.v2.dto.CreateTaskRequest(
                    ids.get(0), null, "EXPORT", payload)));
        } catch (Exception e) {
            if (e instanceof BusinessException business) throw business;
            throw new BusinessException(500, "创建导出任务失败: " + e.getMessage());
        }
    }

    /** 管理员鉴权后的流式下载；存储地址不会进入任务结果或浏览器。 */
    @GetMapping("/artifacts/{artifactId}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable Long artifactId) {
        var download = artifactService.requireDownloadable(artifactId);
        var artifact = download.artifact();
        StreamingResponseBody body = output -> artifactService.stream(download, output);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(artifact.getByteSize())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + artifact.getFileName().replace("\"", "") + "\"")
                .body(body);
    }

    private void requireIds(BatchExportRequest request) {
        if (request == null || request.projectIds() == null || request.projectIds().isEmpty()) {
            throw new BusinessException(400, "请提供要导出的作品 ID 列表");
        }
    }

    /** 批量导出请求体 */
    public record BatchExportRequest(List<Long> projectIds) {
    }
}
