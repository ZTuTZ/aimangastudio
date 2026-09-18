package com.aimanga.v2.controller;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.common.Result;
import com.aimanga.v2.pipeline.PublicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    /** 批量导出:校验通过的打包,失败的写入 summary.json;响应为总 ZIP */
    @PostMapping("/batch")
    public org.springframework.http.ResponseEntity<byte[]> batchExport(@RequestBody BatchExportRequest request) {
        requireIds(request);
        List<Map<String, Object>> items = new ArrayList<>();
        int success = 0;
        int failed = 0;
        Map<String, byte[]> packages = new LinkedHashMap<>();
        for (Long projectId : request.projectIds()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("projectId", projectId);
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                publicationService.writeZip(projectId, bos);
                packages.put("comic-" + projectId + ".zip", bos.toByteArray());
                item.put("exported", true);
                success++;
            } catch (Exception e) {
                item.put("exported", false);
                item.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                failed++;
            }
            items.add(item);
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ZipOutputStream zip = new ZipOutputStream(bos);
            packages.forEach((name, bytes) -> {
                try {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(bytes);
                    zip.closeEntry();
                } catch (Exception ignored) {
                }
            });
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total", request.projectIds().size());
            summary.put("success", success);
            summary.put("failed", failed);
            summary.put("items", items);
            summary.put("exportedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            zip.putNextEntry(new ZipEntry("summary.json"));
            zip.write(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(summary).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            String filename = "comic-batch-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".zip";
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Type", "application/zip")
                    .header("Content-Disposition", "attachment; filename=" + filename)
                    .body(bos.toByteArray());
        } catch (Exception e) {
            throw new BusinessException(500, "批量打包失败: " + e.getMessage());
        }
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
