package com.aimanga.v2.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StageRunScopeTest {

    @Test
    void emptyExplicitPageScopeIsBounded() {
        StageRunScope scope = StageRunScope.pages(List.of());

        assertThat(scope.isUnbounded()).isFalse();
        assertThat(scope.businessType()).isEqualTo("PAGE");
        assertThat(scope.businessIds()).isEmpty();
    }
}
