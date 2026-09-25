package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.dto.export.ComicManifest;
import com.aimanga.v2.dto.export.ComicManifestChapter;
import com.aimanga.v2.dto.export.ComicManifestPage;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.model.PageTextElement;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.repository.ChapterMapper;
import com.aimanga.v2.repository.PageMapper;
import com.aimanga.v2.repository.PageTextElementMapper;
import com.aimanga.v2.repository.ProjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final PageTextElementMapper pageTextElementMapper;
    private final ObjectMapper objectMapper;
    private final RemoteImageFetcher remoteImageFetcher;
    private final PlatformTransactionManager transactionManager;
    private final ExportTempFiles tempFiles;

    // ---------- T7.3 校验 ----------

    public record ValidationIssue(String level, String message) {}

    public record PublicationReport(boolean valid, List<ValidationIssue> issues,
                                    int chapterCount, int pageCount) {}

    public PublicationReport validate(Long projectId) {
        return validateInternal(projectId);
    }

    private PublicationReport validateInternal(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(404, "作品不存在: " + projectId);
        }
        List<ValidationIssue> issues = new ArrayList<>();
        List<PageTextElement> allTextElements = pageTextElementMapper.selectList(
                new LambdaQueryWrapper<PageTextElement>().eq(PageTextElement::getProjectId, projectId));
        Map<Long, List<PageTextElement>> textByPage = new LinkedHashMap<>();
        for (PageTextElement element : allTextElements) {
            textByPage.computeIfAbsent(element.getPageId(), ignored -> new ArrayList<>()).add(element);
        }

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
                if (!PageReadiness.hasCurrentImage(page)) {
                    issues.add(new ValidationIssue("ERROR",
                            "第 " + chapter.getChapterNo() + " 话第 " + no + " 页成品图未就绪或已过期"));
                }
                if (PageReadiness.hasText(page)) {
                    List<PageTextElement> layer = textByPage.getOrDefault(page.getId(), List.of());
                    if (page.getScriptVersion() == null || page.getTextLayoutVersion() == null
                            || !page.getScriptVersion().equals(page.getTextLayoutVersion())) {
                        issues.add(new ValidationIssue("ERROR", "第 " + chapter.getChapterNo() + " 话第 " + no
                                + " 页文本层已过期,请同步后发布"));
                    } else if (layer.isEmpty() || !textLayerCoversBody(page, layer)) {
                        issues.add(new ValidationIssue("ERROR", "第 " + chapter.getChapterNo() + " 话第 " + no
                                + " 页文本层未覆盖当前对白/旁白"));
                    }
                }
                pageTotal++;
            }
        }

        boolean valid = issues.stream().noneMatch(i -> "ERROR".equals(i.level()));
        return new PublicationReport(valid, issues, chapters.size(), pageTotal);
    }

    // ---------- T7.4 导出 ----------

    /** 校验 + 构建 manifest(打包前调用;manifest 数据与 writeZip 共用) */

    /** 校验通过后构建 manifest 并流式写 ZIP(Phase 8.8:不在内存中持有全量 byte[]) */
    public ComicManifest buildManifest(Long projectId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template.execute(status -> buildManifestInternal(projectId));
    }

    /** Write a previously captured immutable publication snapshot. */
    public void writeZip(ComicManifest snapshot, java.io.OutputStream out) {
        writeSnapshotZip(snapshot, out);
    }

    private ComicManifest buildManifestInternal(Long projectId) {
        PublicationReport report = validateInternal(projectId);
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
        List<PageTextElement> textElements = pageTextElementMapper.selectList(
                new LambdaQueryWrapper<PageTextElement>()
                        .eq(PageTextElement::getProjectId, projectId));
        Map<Long, List<PageTextElement>> textByPage = new LinkedHashMap<>();
        for (PageTextElement e : textElements) {
            textByPage.computeIfAbsent(e.getPageId(), k -> new ArrayList<>()).add(e);
        }

        List<ComicManifestChapter> manifestChapters = new ArrayList<>();
        for (Chapter chapter : chapters) {
            List<PageEntity> pages = pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                    .eq(PageEntity::getChapterId, chapter.getId())
                    .orderByAsc(PageEntity::getPageNo));
            List<ComicManifestPage> manifestPages = new ArrayList<>();
            for (PageEntity page : pages) {
                if (!PageReadiness.hasCurrentImage(page)) {
                    continue;
                }
                List<PageTextElement> layer = textByPage.get(page.getId());
                ComicManifestPage.TextLayer textLayer = null;
                if (layer != null && !layer.isEmpty()) {
                    List<Map<String, Object>> elements = new ArrayList<>();
                    for (PageTextElement e : layer) {
                        Map<String, Object> el = new LinkedHashMap<>();
                        el.put("uid", e.getElementUid());
                        el.put("type", e.getElementType());
                        el.put("dialogueIndex", e.getDialogueIndex());
                        el.put("speaker", e.getSpeaker());
                        el.put("text", e.getTextContent());
                        el.put("position", Map.of("x", orZero(e.getX()), "y", orZero(e.getY()),
                                "width", orZero(e.getWidth()), "height", orZero(e.getHeight())));
                        el.put("style", Map.of("fontPreset", safeStr(e.getFontStyle()),
                                "fontSizeRatio", orZero(e.getFontSizeRatio()), "align", safeStr(e.getTextAlign()),
                                "maxLines", e.getMaxLines() == null ? 4 : e.getMaxLines()));
                        Map<String, Object> bubble = new LinkedHashMap<>();
                        bubble.put("preset", e.getBubbleStyle());
                        if (e.getTailX() != null && e.getTailY() != null) {
                            bubble.put("tail", Map.of("x", e.getTailX(), "y", e.getTailY()));
                        }
                        el.put("bubble", bubble);
                        el.put("sortOrder", e.getSortOrder());
                        elements.add(el);
                    }
                    textLayer = new ComicManifestPage.TextLayer("comic-text-layer-1.0", elements);
                }
                manifestPages.add(new ComicManifestPage(page.getPageNo(),
                        page.getGeneratedImageUrl(),
                        filePathOf(chapter.getChapterNo(), page.getPageNo(), page.getGeneratedImageUrl()),
                        textLayer, parseDialogue(page.getDialogue()), page.getNarration(), page.getScriptVersion(),
                        page.getImageScriptVersion(), page.getTextLayoutVersion()));
            }
            manifestChapters.add(new ComicManifestChapter(chapter.getChapterNo(), chapter.getTitle(), manifestPages));
        }
        return new ComicManifest(
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
    }

    /** Phase 8.8 §10.1:流式写 ZIP —— 图片通过 RemoteImageFetcher 流式拉取,内存仅当前缓冲块 */
    public void writeZip(Long projectId, java.io.OutputStream out) {
        ComicManifest snapshot = buildManifest(projectId);
        writeSnapshotZip(snapshot, out);
    }

    private void writeSnapshotZip(ComicManifest snapshot, java.io.OutputStream out) {
        Map<String, ExportTempFiles.Handle> downloaded = new LinkedHashMap<>();
        try {
            Map<String, String> extensions = new LinkedHashMap<>();
            for (ComicManifestChapter chapter : snapshot.chapters()) {
                for (ComicManifestPage page : chapter.pages()) {
                    String key = chapter.chapterNo() + ":" + page.pageNo();
                    try (RemoteImageFetcher.FetchResult fetch = remoteImageFetcher.fetchStream(page.imageUrl());
                         InputStream in = fetch.inputStream()) {
                        String ext = extensionForMime(fetch.mime());
                        ExportTempFiles.Handle image = tempFiles.create(
                                "page-" + chapter.chapterNo() + "-" + page.pageNo() + "-", "." + ext);
                        downloaded.put(key, image);
                        try (OutputStream imageOut = tempFiles.open(image)) {
                            in.transferTo(imageOut);
                        }
                        extensions.put(key, ext);
                    }
                }
            }
            ComicManifest manifest = withResolvedPaths(snapshot, extensions);
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            int pages = 0;
            for (ComicManifestChapter chapter : manifest.chapters()) {
                for (ComicManifestPage page : chapter.pages()) {
                    zip.putNextEntry(new ZipEntry(page.filePath()));
                    java.nio.file.Files.copy(downloaded.get(chapter.chapterNo() + ":" + page.pageNo()).path(), zip);
                    zip.closeEntry();
                    pages++;
                }
            }
            zip.finish();
            log.info("[export] 作品 {} 流式导出完成: {} 话 / {} 页", snapshot.contentUid(),
                    manifest.chapters().size(), pages);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500, "导出打包失败: " + e.getMessage());
        } finally {
            downloaded.values().forEach(ExportTempFiles.Handle::close);
        }
    }

    private boolean textLayerCoversBody(PageEntity page, List<PageTextElement> layer) {
        List<String> expected = new ArrayList<>();
        if (!isBlank(page.getNarration())) expected.add(page.getNarration().trim());
        JsonNode dialogue = parseDialogue(page.getDialogue());
        if (dialogue.isArray()) {
            dialogue.forEach(item -> {
                String line = item.path("line").asText("").trim();
                if (!line.isBlank()) expected.add(line);
            });
        }
        List<String> actual = layer.stream().map(PageTextElement::getTextContent)
                .filter(text -> text != null && !text.isBlank()).map(String::trim).toList();
        return !expected.isEmpty() && expected.stream().allMatch(actual::contains);
    }

    private JsonNode parseDialogue(String json) {
        try {
            JsonNode node = objectMapper.readTree(isBlank(json) ? "[]" : json);
            return node.isArray() ? node : objectMapper.createArrayNode();
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }

    private ComicManifest withResolvedPaths(ComicManifest source, Map<String, String> extensions) {
        List<ComicManifestChapter> chapters = new ArrayList<>();
        for (ComicManifestChapter chapter : source.chapters()) {
            List<ComicManifestPage> pages = new ArrayList<>();
            for (ComicManifestPage page : chapter.pages()) {
                String ext = extensions.get(chapter.chapterNo() + ":" + page.pageNo());
                pages.add(new ComicManifestPage(page.pageNo(), page.imageUrl(),
                        filePathOf(chapter.chapterNo(), page.pageNo(), ext), page.textLayer(), page.dialogue(),
                        page.narration(), page.scriptVersion(), page.imageScriptVersion(), page.textLayoutVersion()));
            }
            chapters.add(new ComicManifestChapter(chapter.chapterNo(), chapter.title(), pages));
        }
        return new ComicManifest(source.schemaVersion(), source.contentUid(), source.title(), source.tagline(),
                source.description(), source.coverUrl(), source.category(), source.tags(), source.seriesStatus(),
                source.aspectRatio(), source.colorMode(), source.complete(), chapters);
    }

    private static String extensionForMime(String mime) {
        return switch (mime == null ? "" : mime.toLowerCase()) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/avif" -> "avif";
            default -> throw new BusinessException(502, "不支持的图片 MIME: " + mime);
        };
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }

    private static double orZero(Double v) {
        return v == null ? 0.0 : v;
    }

    private static String filePathOf(int chapterNo, int pageNo, String urlOrExtension) {
        String value = urlOrExtension == null ? "" : urlOrExtension.toLowerCase();
        String ext;
        if (List.of("png", "jpg", "jpeg", "webp", "gif", "avif").contains(value)) {
            ext = "jpeg".equals(value) ? "jpg" : value;
        } else {
            String path;
            try { path = URI.create(value).getPath(); } catch (Exception e) { path = ""; }
            int dot = path.lastIndexOf('.');
            String candidate = dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
            ext = List.of("png", "jpg", "jpeg", "webp", "gif", "avif").contains(candidate)
                    ? ("jpeg".equals(candidate) ? "jpg" : candidate) : "jpg";
        }
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
