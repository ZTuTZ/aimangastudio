package com.aimanga.v2.ai;

/** 单个 AI 通道的运行参数(来自 system_config,热更新) */
public record ChatConfig(String apiUrl, String apiKey, String model, int timeoutMs) {

    public ChatConfig {
        if (apiUrl != null) {
            apiUrl = apiUrl.replaceAll("/+$", "");
        }
    }
}
