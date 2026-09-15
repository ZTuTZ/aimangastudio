package com.aimanga.v2.pipeline;

import com.aimanga.v2.dto.export.ComicManifest;
import com.aimanga.v2.dto.export.ComicManifestChapter;
import com.aimanga.v2.dto.export.ComicManifestPage;
import com.aimanga.v2.model.app.ComicChapterEntity;
import com.aimanga.v2.model.app.ComicEntity;
import com.aimanga.v2.model.app.ComicPageEntity;
import com.aimanga.v2.repository.app.ComicChapterMapper;
import com.aimanga.v2.repository.app.ComicMapper;
import com.aimanga.v2.repository.app.ComicPageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * APP Importer(Phase 7.6 模拟):comic-content-1.0 包 → 未来 APP 阅读库(comic/comic_chapter/comic_page)。
 * 验证核心约束:整个导入过程完全不接触生产系统的 project.id/user_id/task.id/chapter.id/page.id ——
 * 唯一的关联键是跨系统稳定 ID content_uid;重复导入按 content_uid 幂等覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComicImporterService {

    private final ComicMapper comicMapper;
    private final ComicChapterMapper chapterMapper;
    private final ComicPageMapper pageMapper;
    private final ObjectMapper objectMapper;

    /** 导入 manifest JSON(即导出包内的 manifest.json),返回 {comicId, chapters, pages} */
    public ImportResult importManifest(String manifestJson) {
        ComicManifest manifest;
        try {
            manifest = objectMapper.readValue(manifestJson, ComicManifest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("manifest.json 解析失败: " + e.getMessage());
        }
        if (manifest.contentUid() == null || manifest.contentUid().isBlank()) {
            throw new IllegalArgumentException("manifest 缺少 contentUid");
        }
        if (manifest.chapters() == null || manifest.chapters().isEmpty()) {
            throw new IllegalArgumentException("manifest 不含任何话");
        }

        // ① comic:按 content_uid 幂等 upsert
        ComicEntity comic = comicMapper.selectOne(new LambdaQueryWrapper<ComicEntity>()
                .eq(ComicEntity::getContentUid, manifest.contentUid()));
        if (comic == null) {
            comic = new ComicEntity();
            comic.setContentUid(manifest.contentUid());
            comic.setCreateTime(LocalDateTime.now());
        }
        comic.setTitle(manifest.title());
        comic.setTagline(manifest.tagline());
        comic.setDescription(manifest.description());
        comic.setCoverUrl(manifest.coverUrl());
        comic.setCategory(manifest.category());
        comic.setTags(toJson(manifest.tags()));
        comic.setSeriesStatus(manifest.seriesStatus());
        comic.setAspectRatio(manifest.aspectRatio());
        comic.setColorMode(manifest.colorMode());
        comic.setComplete(Boolean.TRUE.equals(manifest.complete()) ? 1 : 0);
        if (comic.getId() == null) {
            comicMapper.insert(comic);
        } else {
            comicMapper.updateById(comic);
        }

        // ② 话/页:全量重建(话与页均来自 manifest,不携带生产 ID)
        chapterMapper.delete(new LambdaQueryWrapper<ComicChapterEntity>()
                .eq(ComicChapterEntity::getComicId, comic.getId()));
        int pageCount = 0;
        for (ComicManifestChapter chapter : manifest.chapters()) {
            ComicChapterEntity entity = new ComicChapterEntity();
            entity.setComicId(comic.getId());
            entity.setChapterNo(chapter.chapterNo());
            entity.setTitle(chapter.title());
            chapterMapper.insert(entity);
            if (chapter.pages() != null) {
                for (ComicManifestPage page : chapter.pages()) {
                    ComicPageEntity pageEntity = new ComicPageEntity();
                    pageEntity.setChapterId(entity.getId());
                    pageEntity.setPageNo(page.pageNo());
                    pageEntity.setImageUrl(page.imageUrl());
                    pageEntity.setFilePath(page.filePath());
                    pageMapper.insert(pageEntity);
                    pageCount++;
                }
            }
        }
        log.info("[importer] 导入漫画 {}({}): {} 话 / {} 页", manifest.title(), manifest.contentUid(),
                manifest.chapters().size(), pageCount);
        return new ImportResult(comic.getId(), manifest.chapters().size(), pageCount);
    }

    public record ImportResult(Long comicId, int chapters, int pages) {}

    private String toJson(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags == null ? java.util.List.of() : tags);
        } catch (Exception e) {
            return "[]";
        }
    }
}
