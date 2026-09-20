package com.aimanga.v2.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.aimanga.v2.service.TaskPlanningService;

/** 上色任务(Phase 6.6 T6.6.1):merge 通道,输入当前成品图 */
@Slf4j
@Component
public class ColorizeTaskHandler extends PostProcessTaskHandler {

    public ColorizeTaskHandler(PipelineContext ctx, PostProcessService postProcessService,
                               PipelineStageService stageService, ConcurrentStageRunner stageRunner,
                               StageItemCommitService commitService, TaskPlanningService taskPlanningService) {
        super(ctx, postProcessService, stageService, stageRunner, commitService, taskPlanningService);
    }

    @Override
    public String type() {
        return "COLORIZE";
    }

    @Override
    protected String op() {
        return PostProcessService.OP_COLORIZE;
    }
}
