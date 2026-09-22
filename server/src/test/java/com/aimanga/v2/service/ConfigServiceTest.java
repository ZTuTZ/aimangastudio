package com.aimanga.v2.service;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.SystemConfig;
import com.aimanga.v2.repository.SystemConfigMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigServiceTest {

    @Test
    void readsExportLimitsBeyondTheIntegerRange() {
        SystemConfigMapper mapper = mapperWith("export_max_bytes", "8589934592");

        assertThat(new ConfigService(mapper).getLong("export_max_bytes", 1L))
                .isEqualTo(8_589_934_592L);
    }

    @Test
    void rejectsInvalidConfiguredLongInsteadOfSilentlyUsingTheDefault() {
        for (String value : List.of("-1", "not-a-number", "9223372036854775808")) {
            ConfigService service = new ConfigService(mapperWith("export_max_bytes", value));
            assertThatThrownBy(() -> service.getLong("export_max_bytes", 1_073_741_824L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("export_max_bytes");
        }
    }

    @Test
    void invalidExportLimitIsRejectedBeforeSavingAnyConfiguration() {
        SystemConfigMapper mapper = mock(SystemConfigMapper.class);
        ConfigService service = new ConfigService(mapper);

        assertThatThrownBy(() -> service.save(Map.of(
                "task_max_concurrency", "8",
                "export_max_bytes", "invalid")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("export_max_bytes");
        verify(mapper, never()).insert(any(SystemConfig.class));
        verify(mapper, never()).updateById(any(SystemConfig.class));
    }

    private static SystemConfigMapper mapperWith(String key, String value) {
        SystemConfigMapper mapper = mock(SystemConfigMapper.class);
        SystemConfig config = new SystemConfig();
        config.setId(1L);
        config.setConfigKey(key);
        config.setConfigValue(value);
        when(mapper.selectList(any())).thenReturn(List.of(config));
        return mapper;
    }
}
