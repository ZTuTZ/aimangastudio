package com.aimanga.v2.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 局部重绘任务(Phase 6.6 T6.6.3):原图+遮罩+重绘提示词,merge 通道 */
@Slf4j
@Component
public class RepaintTaskHandler extends PostProcessTaskHandler {

    public RepaintTaskHandler(PipelineContext ctx, PostProcessService postProcessService,
                               PipelineStageService stageService, ConcurrentStageRunner stageRunner,
                               StageItemCommitService commitService) {
        super(ctx, postProcessService, stageService, stageRunner, commitService);
    }

    @Override
    public String type() {
        return "REPAINT";
    }

    @Override
    protected String op() {
        return PostProcessService.OP_REPAINT;
    }
}
