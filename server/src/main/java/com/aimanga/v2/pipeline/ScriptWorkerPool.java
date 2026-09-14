package com.aimanga.v2.pipeline;

import com.aimanga.v2.service.ConfigService;
import org.springframework.stereotype.Component;

/**
 * 脚本 Worker 池(Phase 5.11 T5.11.6):
 * 并发 = script_item_concurrency(默认 5,热更新),队列 = script_queue_size(默认 50)。
 * SCRIPT 文本 AI 请求使用独立线程池,不再占用生图 Worker 池;
 * 最终仍受 ai_text_concurrency(Redis 信号量,层④)保护。
 */
@Component
public class ScriptWorkerPool extends AbstractStageWorkerPool {

    public ScriptWorkerPool(ConfigService configService) {
        super(configService);
    }

    @Override
    protected String concurrencyKey() {
        return "script_item_concurrency";
    }

    @Override
    protected int concurrencyDefault() {
        return 5;
    }

    @Override
    protected int concurrencyMax() {
        return 32;
    }

    @Override
    protected String queueSizeKey() {
        return "script_queue_size";
    }

    @Override
    protected int queueSizeDefault() {
        return 50;
    }

    @Override
    protected String threadNamePrefix() {
        return "script-gen";
    }
}
