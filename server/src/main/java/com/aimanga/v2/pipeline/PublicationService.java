package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.dto.export.ComicManifest;
import com.aimanga.v2.dto.export.ComicManifestChapter;
import com.aimanga.v2.dto.export.ComicManifestPage;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.repository.ChapterMapper;
import com.aimanga.v2.repository.PageMapper;
import com.aimanga.v2.repository.ProjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 发布校验与 comic-content-1.0 导出(Phase 7.3/7.4)。
 *
 * PublicationValidator(T7.3):导出前硬校验——
 * - Comic:title/cover 必填,category/tags 建议项(警告);
 * - Chapter:chapter_no 从 1 连续、title 必填、至少一页;
 * - Page:page_no 从 1 连续、generate_status=SUCCESS、generated_image_url 非空。
 * 不要求 SHEET/ASSET_REF 存在(AI 生产资产不是阅读数据)。
 *
 * 导出(T7.4):ZIP = manifest.json(comic-content-1.0) + 全部成品页图片(按 第N话/第N页.png 归档)。
 * manifest 不含 user_id/task/pipeline/prompt/retry 等生产字段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicationService {

    private final ProjectMapper projectMapper;
    private final ChapterMapper chapterMapper;
    private final PageMapper pageMapper;
    private final ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ---------- T7.3 校验 ----------

    public record ValidationIssue(String level, String message) {}

    public record PublicationReport(boolean valid, List<ValidationIssue> issues,
                                    int chapterCount, int pageCount) {}

    public PublicationReport validate(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(404, "作品不存在: " + projectId);
        }
        List<ValidationIssue> issues = new ArrayList<>();

        // Comic 层
        if (isBlank(project.getTitle())) issues.add(new ValidationIssue("ERROR", "作品标题为空"));
        if (isBlank(project.getCoverUrl())) issues.add(new ValidationIssue("ERROR", "缺少封面(cover_url)"));
        if (isBlank(project.getCategory())) issues.add(new ValidationIssue("WARNING", "未设置主分类"));
        if (isBlank(project.getTags()) || "[]".equals(project.getTags().trim())) {
            issues.add(new ValidationIssue("WARNING", "未设置标签"));
        }

        // Chapter 层
        List<Chapter> chapters = chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getProjectId, projectId)
                .orderByAsc(Chapter::getChapterNo));
        if (chapters.isEmpty()) {
            issues.add(new ValidationIssue("ERROR", "没有任何话"));
        }
        int expectedChapterNo = 1;
        for (Chapter chapter : chapters) {
            int no = chapter.getChapterNo() == null ? -1 : chapter.getChapterNo();
            if (no != expectedChapterNo) {
                issues.add(new ValidationIssue("ERROR", "话序号不连续:期望第 " + expectedChapterNo + " 话,实际第 " + no + " 话"));
            }
            expectedChapterNo = Math.max(expectedChapterNo, no) + 1;
            if (isBlank(chapter.getTitle())) {
                issues.add(new ValidationIssue("ERROR", "第 " + no + " 话标题为空"));
            }
        }

        // Page 层
        int pageTotal = 0;
        for (Chapter chapter : chapters) {
            List<PageEntity> pages = pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                    .eq(PageEntity::getChapterId, chapter.getId())
                    .orderByAsc(PageEntity::getPageNo));
            if (pages.isEmpty()) {
                issues.add(new ValidationIssue("ERROR", "第 " + chapter.getChapterNo() + " 话没有页面"));
                continue;
            }
            int expectedPageNo = 1;
            for (PageEntity page : pages) {
                int no = page.getPageNo() == null ? -1 : page.getPageNo();
                if (no != expectedPageNo) {
                    issues.add(new ValidationIssue("ERROR",
                            "第 " + chapter.getChapterNo() + " 话页号不连续:期望 " + expectedPageNo + ",实际 " + no));
                }
                expectedPageNo = Math.max(expectedPageNo, no) + 1;
                if (page.getGenerateStatus() == null || page.getGenerateStatus() != PageEntity.GEN_SUCCESS
                        || isBlank(page.getGeneratedImageUrl())) {
                    issues.add(new ValidationIssue("ERROR",
                            "第 " + chapter.getChapterNo() + " 话第 " + no + " 页成品图未就绪"));
                }
                pageTotal++;
            }
        }

        boolean valid = issues.stream().noneMatch(i -> "ERROR".equals(i.level()));
        return new PublicationReport(valid, issues, chapters.size(), pageTotal);
    }

    // ---------- T7.4 导出 ----------

    /** 校验通过后构建 manifest 并打包 ZIP(manifest.json + 成品页图片) */
    public byte[] exportZip(Long projectId) {
        PublicationReport report = validate(projectId);
        if (!report.valid()) {
            String firstError = report.issues().stream()
                    .filter(i -> "ERROR".equals(i.level())).findFirst()
                    .map(ValidationIssue::message).orElse("校验未通过");
            throw new BusinessException(400, "发布校验未通过: " + firstError);
        }
        Project project = projectMapper.selectById(projectId);
        List<Chapter> chapters = chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getProjectId, projectId)
                .orderByAsc(Chapter::getChapterNo));

        // manifest(仅阅读所需字段)
        List<ComicManifestChapter> manifestChapters = new ArrayList<>();
        for (Chapter chapter : chapters) {
            List<PageEntity> pages = pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                    .eq(PageEntity::getChapterId, chapter.getId())
                    .orderByAsc(PageEntity::getPageNo));
            List<ComicManifestPage> manifestPages = new ArrayList<>();
            for (PageEntity page : pages) {
                if (page.getGenerateStatus() == null || page.getGenerateStatus() != PageEntity.GEN_SUCCESS
                        || isBlank(page.getGeneratedImageUrl())) {
                    continue; // 双保险:manifest 只收正式成品页
                }
                manifestPages.add(new ComicManifestPage(page.getPageNo(),
                        page.getGeneratedImageUrl(), filePathOf(chapter.getChapterNo(), page.getPageNo(), page.getGeneratedImageUrl())));
            }
            manifestChapters.add(new ComicManifestChapter(chapter.getChapterNo(), chapter.getTitle(), manifestPages));
        }
        ComicManifest manifest = new ComicManifest(
                ComicManifest.SCHEMA_VERSION,
                project.getContentUid(),
                project.getTitle(),
                project.getTagline(),
                project.getDescription(),
                project.getCoverUrl(),
                project.getCategory(),
                parseTags(project.getTags()),
                project.getSeriesStatus(),
                project.getAspectRatio(),
                project.getColorMode(),
                project.getStatus() != null && project.getStatus() == Project.STATUS_DONE,
                manifestChapters);

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ZipOutputStream zip = new ZipOutputStream(bos);
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(manifest).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            // 成品页图片归档
            for (ComicManifestChapter chapter : manifestChapters) {
                for (ComicManifestPage page : chapter.pages()) {
                    byte[] image = fetchImage(page.imageUrl());
                    zip.putNextEntry(new ZipEntry(page.filePath()));
                    zip.write(image);
                    zip.closeEntry();
                }
            }
            zip.finish();
            log.info("[export] 作品 {} 导出完成: {} 话 / {} 页", projectId, manifestChapters.size(),
                    manifestChapters.stream().mapToInt(c -> c.pages().size()).sum());
            return bos.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500, "导出打包失败: " + e.getMessage());
        }
    }

    private byte[] fetchImage(String url) {
        try {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new BusinessException(500, "拉取页面图片失败(" + response.statusCode() + "): " + url);
            }
            return response.body();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500, "拉取页面图片失败: " + url + " (" + e.getMessage() + ")");
        }
    }

    private static String filePathOf(int chapterNo, int pageNo, String url) {
        String ext = url.toLowerCase().endsWith(".png") ? "png" : "jpg";
        return "第" + chapterNo + "话/第" + pageNo + "页." + ext;
    }

    private List<String> parseTags(String tagsJson) {
        try {
            JsonNode node = objectMapper.readTree(isBlank(tagsJson) ? "[]" : tagsJson);
            List<String> list = new ArrayList<>();
            if (node.isArray()) node.forEach(n -> list.add(n.asText()));
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
