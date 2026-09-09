package com.aimanga.v2.ai;

/**
 * 单个 AI 通道的运行参数(来自 system_config,热更新)。
 * protocol: gemini = {url}/v1beta/models/{model}:generateContent(默认,兼容 GeekAI 网关);
 *           openai = {url}/chat/completions(智谱 GLM/DeepSeek/OpenAI 系文本接口)。
 */
public record ChatConfig(String apiUrl, String apiKey, String model, int timeoutMs, String protocol) {

    public ChatConfig {
        if (apiUrl != null) {
            apiUrl = apiUrl.replaceAll("/+$", "");
        }
        if (protocol == null || protocol.isBlank()) {
            protocol = "gemini";
        }
    }

    public boolean openaiProtocol() {
        return "openai".equalsIgnoreCase(protocol);
    }
}
