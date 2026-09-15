package com.aimanga.v2.pipeline;

import com.aimanga.v2.dto.export.ComicManifest;
import com.aimanga.v2.model.app.ComicChapterEntity;
import com.aimanga.v2.model.app.ComicEntity;
import com.aimanga.v2.model.app.ComicPageEntity;
import com.aimanga.v2.repository.app.ComicChapterMapper;
import com.aimanga.v2.repository.app.ComicMapper;
import com.aimanga.v2.repository.app.ComicPageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7.6 APP Importer 模拟测试:
 * comic-content-1.0 包不含任何生产 ID 也能还原 漫画→话→页;重复导入幂等覆盖。
 */
class ComicImporterServiceTest {

    private ComicMapper comicMapper;
    private ComicChapterMapper chapterMapper;
    private ComicPageMapper pageMapper;
    private ComicImporterService importer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        comicMapper = mock(ComicMapper.class);
        chapterMapper = mock(ComicChapterMapper.class);
        pageMapper = mock(ComicPageMapper.class);
        importer = new ComicImporterService(comicMapper, chapterMapper, pageMapper, objectMapper);
    }

    private String manifestJson(boolean withChapters) {
        String chapters = withChapters ? """
                "chapters": [
                  {"chapterNo":1,"title":"第1话 开端","pages":[
                     {"pageNo":1,"imageUrl":"https://oss/a/p1.jpg","filePath":"第1话/第1页.jpg"},
                     {"pageNo":2,"imageUrl":"https://oss/a/p2.jpg","filePath":"第1话/第2页.jpg"}]},
                  {"chapterNo":2,"title":"第2话 转折","pages":[
                     {"pageNo":1,"imageUrl":"https://oss/b/p1.jpg","filePath":"第2话/第1页.jpg"}]}
                ]""" : "\"chapters\": []";
        return """
                {
                  "schemaVersion": "comic-content-1.0",
                  "contentUid": "11111111-2222-3333-4444-555555555555",
                  "title": "测试漫画",
                  "tagline": "一句话",
                  "description": "简介",
                  "coverUrl": "https://oss/cover.jpg",
                  "category": "古风",
                  "tags": ["重生","系统"],
                  "seriesStatus": 2,
                  "aspectRatio": "3:4",
                  "colorMode": "partial",
                  "complete": true,
                  %s
                }
                """.formatted(chapters);
    }

    @Test
    void importManifest_createsComicWithoutProductionIds() {
        when(comicMapper.selectOne(any())).thenReturn(null);

        ComicImporterService.ImportResult result = importer.importManifest(manifestJson(true));

        assertThat(result.chapters()).isEqualTo(2);
        assertThat(result.pages()).isEqualTo(3);

        ArgumentCaptor<ComicEntity> comicCaptor = ArgumentCaptor.forClass(ComicEntity.class);
        verify(comicMapper).insert(comicCaptor.capture());
        ComicEntity comic = comicCaptor.getValue();
        // 核心约束:只有 content_uid,不含任何生产系统 ID 字段
        assertThat(comic.getContentUid()).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(comic.getTitle()).isEqualTo("测试漫画");
        assertThat(comic.getComplete()).isEqualTo(1);

        ArgumentCaptor<ComicChapterEntity> chapterCaptor = ArgumentCaptor.forClass(ComicChapterEntity.class);
        verify(chapterMapper, times(2)).insert(chapterCaptor.capture());
        assertThat(chapterCaptor.getAllValues())
                .extracting(ComicChapterEntity::getChapterNo)
                .containsExactly(1, 2);

        ArgumentCaptor<ComicPageEntity> pageCaptor = ArgumentCaptor.forClass(ComicPageEntity.class);
        verify(pageMapper, times(3)).insert(pageCaptor.capture());
        assertThat(pageCaptor.getAllValues())
                .extracting(ComicPageEntity::getImageUrl)
                .containsExactly("https://oss/a/p1.jpg", "https://oss/a/p2.jpg", "https://oss/b/p1.jpg");
    }

    @Test
    void importManifest_existingComic_updatedByIdempotentUpsert() {
        ComicEntity existing = new ComicEntity();
        existing.setId(77L);
        existing.setContentUid("11111111-2222-3333-4444-555555555555");
        when(comicMapper.selectOne(any())).thenReturn(existing);

        importer.importManifest(manifestJson(true));

        // 已存在 → 更新而非新建;话全量重建
        verify(comicMapper).updateById(existing);
        verify(comicMapper, times(0)).insert(any(ComicEntity.class));
        verify(chapterMapper, times(2)).insert(any(ComicChapterEntity.class));
    }

    @Test
    void importManifest_emptyChapters_rejected() {
        assertThatThrownBy(() -> importer.importManifest(manifestJson(false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不含任何话");
    }

    @Test
    void importManifest_missingContentUid_rejected() {
        String bad = manifestJson(true).replace(
                "\"contentUid\": \"11111111-2222-3333-4444-555555555555\",", "");
        assertThatThrownBy(() -> importer.importManifest(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contentUid");
    }
}
