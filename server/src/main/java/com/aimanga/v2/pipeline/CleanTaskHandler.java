package com.aimanga.v2.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 清晰化任务(Phase 6.6 T6.6.2):merge 通道,输入当前成品图 */
@Slf4j
@Component
public class CleanTaskHandler extends PostProcessTaskHandler {

    public CleanTaskHandler(PipelineContext ctx, PostProcessService postProcessService,
                               PipelineStageService stageService, ConcurrentStageRunner stageRunner,
                               StageItemCommitService commitService) {
        super(ctx, postProcessService, stageService, stageRunner, commitService);
    }

    @Override
    public String type() {
        return "CLEAN";
    }

    @Override
    protected String op() {
        return PostProcessService.OP_COLORIZE;
    }
}
