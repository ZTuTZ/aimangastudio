package com.aimanga.v2.pipeline;

import com.aimanga.v2.service.ConfigService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExportTempFilesTest {
    @Test
    void outputLimitAcceptsExactBoundaryAndRejectsOneMoreByteWithoutDoubleCounting() throws Exception {
        ExportTempFiles files = new ExportTempFiles(mock(ConfigService.class));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var limited = files.limited(bytes, 5);

        limited.write(new byte[]{1, 2, 3, 4}, 0, 4);
        limited.write(5);
        assertThat(bytes.toByteArray()).hasSize(5);
        assertThatThrownBy(() -> limited.write(6)).isInstanceOf(IOException.class)
                .hasMessageContaining("超过限制");
    }

    @Test
    void sharedTempBudgetIsReleasedOnlyAfterFileDeletion() throws Exception {
        ConfigService config = mock(ConfigService.class);
        when(config.getLong("export_temp_max_bytes", 2_147_483_648L)).thenReturn(5L);
        ExportTempFiles files = new ExportTempFiles(config);
        var first = files.create("budget-", ".tmp");
        try {
            try (var output = files.open(first)) {
                output.write(new byte[]{1, 2, 3, 4, 5});
            }
            var second = files.create("budget-", ".tmp");
            try {
                assertThatThrownBy(() -> {
                    try (var output = files.open(second)) { output.write(1); }
                }).isInstanceOf(IOException.class).hasMessageContaining("预算不足");
            } finally {
                second.close();
            }
        } finally {
            first.close();
        }
        var afterRelease = files.create("budget-", ".tmp");
        try (afterRelease; var output = files.open(afterRelease)) {
            output.write(1);
        }
    }
}
