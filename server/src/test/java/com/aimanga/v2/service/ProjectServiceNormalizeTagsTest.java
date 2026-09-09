package com.aimanga.v2.service;

import com.aimanga.v2.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectServiceNormalizeTagsTest {

    @Test
    void blankBecomesEmptyArray() {
        assertThat(ProjectService.normalizeTags(null)).isEqualTo("[]");
        assertThat(ProjectService.normalizeTags("")).isEqualTo("[]");
        assertThat(ProjectService.normalizeTags("   ")).isEqualTo("[]");
    }

    @Test
    void validArrayPassesThrough() {
        assertThat(ProjectService.normalizeTags("[\"重生\",\"系统\"]")).isEqualTo("[\"重生\",\"系统\"]");
        assertThat(ProjectService.normalizeTags(" [] ")).isEqualTo("[]");
    }

    @Test
    void nonArrayRejected() {
        assertThatThrownBy(() -> ProjectService.normalizeTags("{\"tag\":\"重生\"}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("JSON 数组");
        assertThatThrownBy(() -> ProjectService.normalizeTags("重生,系统"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("JSON 数组");
        assertThatThrownBy(() -> ProjectService.normalizeTags("not json at all"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("JSON 数组");
    }
}
