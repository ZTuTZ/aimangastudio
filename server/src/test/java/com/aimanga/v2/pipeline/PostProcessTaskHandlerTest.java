package com.aimanga.v2.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostProcessTaskHandlerTest {

    @Test
    void eachHandlerUsesItsOwnPostProcessOperation() {
        assertThat(new ColorizeTaskHandler(null, null, null, null, null).op())
                .isEqualTo(PostProcessService.OP_COLORIZE);
        assertThat(new CleanTaskHandler(null, null, null, null, null).op())
                .isEqualTo(PostProcessService.OP_CLEAN);
        assertThat(new RepaintTaskHandler(null, null, null, null, null).op())
                .isEqualTo(PostProcessService.OP_REPAINT);
    }
}
