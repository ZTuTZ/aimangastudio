package com.aimanga.v2.ai;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.storage.StorageService;
import com.aimanga.v2.task.RedisSemaphores;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;

/**
 * 统一 AI 服务(业务层):按通道读取配置 → AiClient 调用 → 产出图统一转存 OSS。
 * 通道:text(文本) / image(生图) / merge(编辑/合并)。
 * 并发:每个通道经 Redis 信号量限流(层④,ai_{channel}_concurrency),配置热更新即时生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    public static final String CHANNEL_TEXT = "text";
    public static final String CHANNEL_IMAGE = "image";
    public static final String CHANNEL_MERGE = "merge";

    private final ConfigService configService;
    private final AiClient aiClient;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final RedisSemaphores semaphores;

    /** 文本对话 */
    public String chat(String channel, String prompt, List<String> images) {
        acquire(channel);
        try {
            return aiClient.chatText(cfg(channel), prompt, images == null ? List.of() : images);
        } finally {
            semaphores.releaseAi(channel);
        }
    }

    /** JSON 契约对话:解析失败自动重试 1 次(每次尝试都在通道限流内) */
    public <T> T chatJson(String channel, String prompt, Class<T> clazz) {
        String suffix = "\n\n只返回合法 JSON,不要 Markdown。";
        BusinessException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String raw = chat(channel, prompt + suffix, List.of());
            try {
                String json = raw.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
                return objectMapper.readValue(json, clazz);
            } catch (Exception e) {
                last = new BusinessException(502, "AI 返回的 JSON 解析失败: " + e.getMessage());
                log.warn("[ai] chatJson 第 {} 次解析失败: {}", attempt + 1, e.getMessage());
            }
        }
        throw last;
    }

    /** 生图:产出统一转存 OSS,返回可访问 URL(每次尝试都在通道限流内) */
    public String generateImage(String channel, String prompt, List<String> images, String aspect, Long userId) {
        ChatConfig cfg = cfg(channel);
        BusinessException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            acquire(channel);
            try {
                boolean gemini = cfg.model() != null && cfg.model().toLowerCase().contains("gemini");
                log.info("[ai] 生图 channel={} model={} aspect={} 走Gemini={} 参考图={}",
                        channel, cfg.model(), aspect, gemini, images == null ? 0 : images.size());
                String raw = gemini
                        ? aiClient.geminiImage(cfg, prompt, images, aspect)
                        : aiClient.openAiImage(cfg, prompt, images, aspect);
                return persist(raw, userId);
            } catch (BusinessException e) {
                last = e;
                log.warn("[ai] 生图第 {} 次失败: {}", attempt + 1, e.getMessage());
            } finally {
                semaphores.releaseAi(channel);
            }
        }
        throw last;
    }

    /** AI 通道限流获取:最多等待 60s(流水线并发受控),超时视为通道饱和 */
    private void acquire(String channel) {
        if (!semaphores.acquireAi(channel)) {
            throw new BusinessException(429, "AI 通道 " + channel + " 并发已满,请稍后重试");
        }
    }

    /** 原始引用(http URL / data:base64)→ OSS 可访问 URL */
    private String persist(String raw, Long userId) {
        if (raw.startsWith("data:")) {
            int comma = raw.indexOf(',');
            String meta = raw.substring(5, comma);
            String mime = meta.split(";")[0];
            byte[] bytes = Base64.getDecoder().decode(raw.substring(comma + 1));
            return storageService.saveImage("pages", userId, bytes, mime.contains("jpeg") ? "jpg" : "png");
        }
        return storageService.saveImageFromUrl("pages", userId, raw);
    }

    private ChatConfig cfg(String channel) {
        String prefix = "ai_" + channel + "_";
        ChatConfig config = new ChatConfig(
                configService.getString(prefix + "api_url"),
                configService.getString(prefix + "api_key"),
                configService.getString(prefix + "model"),
                configService.getInt(prefix + "timeout", 120000),
                configService.getString(prefix + "protocol"));
        if (config.model() == null || config.model().isBlank()) {
            config = new ChatConfig(config.apiUrl(), config.apiKey(), "gemini-2.5-flash", config.timeoutMs(), config.protocol());
        }
        return config;
    }
}
