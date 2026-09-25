package com.aimanga.v2.pipeline;

import com.aimanga.v2.dto.export.ComicManifest;
import com.aimanga.v2.dto.export.ComicManifestChapter;
import com.aimanga.v2.dto.export.ComicManifestPage;
import com.aimanga.v2.repository.ChapterMapper;
import com.aimanga.v2.repository.PageMapper;
import com.aimanga.v2.repository.PageTextElementMapper;
import com.aimanga.v2.repository.ProjectMapper;
import com.aimanga.v2.service.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicationServiceTempBudgetTest {
    @Test
    void downloadedImagesShareTheExportTempBudget() {
        Fixture fixture = fixture(5L);
        assertThatThrownBy(() -> fixture.service().writeZip(fixture.snapshot(), new ByteArrayOutputStream()))
                .hasMessageContaining("导出临时磁盘预算不足");
    }

    @Test
    void successfulExportReleasesDownloadedImageReservations() throws Exception {
        Fixture fixture = fixture(10L);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        fixture.service().writeZip(fixture.snapshot(), archive);

        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        assertThat(entries).hasSize(3).contains("manifest.json");
        var next = fixture.tempFiles().create("budget-after-", ".tmp");
        try (next; var output = fixture.tempFiles().open(next)) {
            output.write(new byte[10]);
        }
    }

    private Fixture fixture(long budget) {
        ConfigService config = mock(ConfigService.class);
        when(config.getLong("export_temp_max_bytes", 2_147_483_648L)).thenReturn(budget);
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetchStream("https://images.example/one.png"))
                .thenAnswer(invocation -> new RemoteImageFetcher.FetchResult(
                        new ByteArrayInputStream(new byte[4]), "image/png"));
        when(fetcher.fetchStream("https://images.example/two.png"))
                .thenAnswer(invocation -> new RemoteImageFetcher.FetchResult(
                        new ByteArrayInputStream(new byte[4]), "image/png"));
        ExportTempFiles tempFiles = new ExportTempFiles(config);
        PublicationService service = new PublicationService(mock(ProjectMapper.class),
                mock(ChapterMapper.class), mock(PageMapper.class), mock(PageTextElementMapper.class),
                new ObjectMapper(), fetcher, mock(PlatformTransactionManager.class), tempFiles);
        ComicManifest snapshot = new ComicManifest(ComicManifest.SCHEMA_VERSION,
                "00000000-0000-0000-0000-000000000007", "测试", null, null, null,
                null, List.of(), null, null, null, false,
                List.of(new ComicManifestChapter(1, "第一话", List.of(
                        new ComicManifestPage(1, "https://images.example/one.png", "p1.png", null,
                                null, null, null, null, null),
                        new ComicManifestPage(2, "https://images.example/two.png", "p2.png", null,
                                null, null, null, null, null)))));

        return new Fixture(service, tempFiles, snapshot);
    }

    private record Fixture(PublicationService service, ExportTempFiles tempFiles, ComicManifest snapshot) { }
}
