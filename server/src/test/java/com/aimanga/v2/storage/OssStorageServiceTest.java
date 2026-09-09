package com.aimanga.v2.storage;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.service.ConfigService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OssStorageServiceTest {

    private ConfigService mockConfig(String accessKey, String secret, String bucket) {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getString("oss_access_key")).thenReturn(accessKey);
        when(configService.getString("oss_access_secret")).thenReturn(secret);
        when(configService.getString("oss_bucket")).thenReturn(bucket);
        when(configService.getString("oss_endpoint")).thenReturn("");
        when(configService.getString("oss_region")).thenReturn("oss-cn-hangzhou");
        when(configService.getString("oss_image_process")).thenReturn("x-oss-process=image/resize,w_600");
        return configService;
    }

    @Test
    void unconfigured_throwsClearError() {
        OssStorageService service = new OssStorageService(mockConfig("", "", ""));
        assertThat(service.isConfigured()).isFalse();
        assertThatThrownBy(() -> service.saveImage("uploads", 1L, new byte[]{1}, "png"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先在系统配置中填写 OSS 参数");
    }

    @Test
    void thumbnail_appendsProcessParamOnlyForOwnBucket() {
        OssStorageService service = new OssStorageService(mockConfig("ak", "sk", "mybucket"));
        String own = service.thumbnail("https://mybucket.oss-cn-hangzhou.aliyuncs.com/pages/1.png");
        assertThat(own).isEqualTo("https://mybucket.oss-cn-hangzhou.aliyuncs.com/pages/1.png?x-oss-process=image/resize,w_600");
        String foreign = service.thumbnail("https://cdn.other.com/img.png");
        assertThat(foreign).isEqualTo("https://cdn.other.com/img.png");
        assertThat(service.thumbnail(null)).isNull();
    }
}
