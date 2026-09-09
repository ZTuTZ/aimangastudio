package com.aimanga.v2.ai;

import com.aimanga.v2.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * AI HTTP 客户端(协议层,不含业务):
 * - Gemini 兼容:POST {url}/v1beta/models/{model}:generateContent(支持 imageConfig.aspectRatio 与参考图)
 * - OpenAI 兼容生图:POST {url}/v1/images/generations(size 映射 + 参考图)
 * - 响应解析兼容:OpenAI choices / Gemini candidates / 直返 URL / 文本内嵌 URL
 * 可用 MockWebServer 直接单测(三种响应格式)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiClient {

    /** OpenAI 系生图端点 size 映射(与旧版/heyi 对齐) */
    private static final Map<String, String> IMAGE_SIZES = Map.of(
            "A4", "832x1248", "3:4", "832x1248", "2:3", "832x1248",
            "1:1", "1024x1024", "16:9", "1344x768");

    private static final List<String> IMAGE_EXTS = List.of("png", "jpe?g", "webp", "gif", "avif", "bmp");

    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ---------- 文本 ----------

    /** 文本对话:按协议分派(Gemini generateContent / OpenAI chat.completions),响应多格式解析 */
    public String chatText(ChatConfig cfg, String prompt, List<String> images) {
        JsonNode data = cfg.openaiProtocol()
                ? postChatCompletions(cfg, prompt, images)
                : postGenerateContent(cfg, prompt, images, false, null);
        String text = extractText(data);
        if (text == null || text.isBlank()) {
            throw new BusinessException(502, "模型未返回文本");
        }
        return text;
    }

    /** OpenAI 兼容文本对话(智谱 GLM / DeepSeek / OpenAI 系) */
    private JsonNode postChatCompletions(ChatConfig cfg, String prompt, List<String> images) {
        if (blank(cfg.apiUrl()) || blank(cfg.apiKey())) {
            throw new BusinessException(400, "请先在系统配置中填写 AI 接口地址与 API Key");
        }
        try {
            ObjectNode message = objectMapper.createObjectNode().put("role", "user");
            if (images == null || images.isEmpty()) {
                message.put("content", prompt);
            } else {
                var content = objectMapper.createArrayNode();
                content.addObject().put("type", "text").put("text", prompt);
                for (String image : images) {
                    content.addObject().put("type", "image_url")
                            .set("image_url", objectMapper.createObjectNode().put("url", image));
                }
                message.set("content", content);
            }
            ObjectNode body = objectMapper.createObjectNode()
                    .put("model", cfg.model())
                    .set("messages", objectMapper.createArrayNode().add(message));
            HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.apiUrl() + "/chat/completions"))
                    .timeout(Duration.ofMillis(cfg.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = send(request, cfg.timeoutMs());
            String raw = response.body();
            if (response.statusCode() >= 400) {
                throw new BusinessException(502, httpError(response.statusCode(), raw));
            }
            return objectMapper.readTree(raw);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "AI 请求失败: " + e.getMessage());
        }
    }

    private JsonNode postGenerateContent(ChatConfig cfg, String prompt, List<String> images,
                                         boolean wantImage, String aspect) {
        if (blank(cfg.apiUrl()) || blank(cfg.apiKey())) {
            throw new BusinessException(400, "请先在系统配置中填写 AI 接口地址与 API Key");
        }
        try {
            String url = cfg.apiUrl() + "/v1beta/models/" + cfg.model() + ":generateContent";
            List<Object> parts = new ArrayList<>();
            parts.add(objectMapper.createObjectNode().put("text", prompt));
            if (images != null) {
                for (String image : images) {
                    parts.add(imagePart(objectMapper, image));
                }
            }
            var contents = objectMapper.createArrayNode().add(
                    objectMapper.createObjectNode().put("role", "user").set("parts", objectMapper.valueToTree(parts)));
            ObjectNode body = objectMapper.createObjectNode().set("contents", contents);
            if (wantImage) {
                ObjectNode generationConfig = objectMapper.createObjectNode();
                generationConfig.putArray("responseModalities").add("IMAGE").add("TEXT");
                String ratio = normalizeAspect(aspect);
                if (ratio != null) {
                    generationConfig.set("imageConfig", objectMapper.createObjectNode().put("aspectRatio", ratio));
                }
                body.set("generationConfig", generationConfig);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(cfg.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            CompletableFuture<HttpResponse<String>> future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response = future.get(cfg.timeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
            String raw = response.body();
            if (response.statusCode() >= 400) {
                // 网关偶发在错误响应里直接带图 URL
                if (wantImage) {
                    String urlInText = extractImageUrlFromText(raw);
                    if (urlInText != null) {
                        return objectMapper.createObjectNode().put("url", urlInText);
                    }
                }
                throw new BusinessException(502, httpError(response.statusCode(), raw));
            }
            try {
                return objectMapper.readTree(raw);
            } catch (Exception e) {
                // 非 JSON(网关可能直接返回文本/URL)
                return objectMapper.createObjectNode().put("raw", raw);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "AI 请求失败: " + e.getMessage());
        }
    }

    private HttpResponse<String> send(HttpRequest request, long timeoutMs) throws Exception {
        CompletableFuture<HttpResponse<String>> future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // ---------- 生图 ----------

    /** Gemini 系生图,返回原始图片引用(http URL 或 data:base64) */
    public String geminiImage(ChatConfig cfg, String prompt, List<String> images, String aspect) {
        JsonNode data = postGenerateContent(cfg, prompt, images, true, aspect);
        String url = extractImageUrl(data);
        if (url == null) {
            String text = extractText(data);
            throw new BusinessException(502, text == null || text.isBlank() ? "模型未返回图片" : "模型未返回图片: " + text);
        }
        return url;
    }

    /** OpenAI 系生图(/v1/images/generations),返回原始图片引用(http URL 或 data:base64) */
    public String openAiImage(ChatConfig cfg, String prompt, List<String> images, String aspect) {
        if (blank(cfg.apiUrl()) || blank(cfg.apiKey())) {
            throw new BusinessException(400, "请先在系统配置中填写 AI 接口地址与 API Key");
        }
        try {
            ObjectNode body = objectMapper.createObjectNode()
                    .put("model", cfg.model())
                    .put("prompt", prompt)
                    .put("n", 1)
                    .put("size", IMAGE_SIZES.getOrDefault(aspect == null ? "" : aspect, "1024x1024"));
            if (images != null && images.size() == 1) {
                body.put("image", images.get(0));
            } else if (images != null && !images.isEmpty()) {
                body.set("image", objectMapper.valueToTree(images));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.apiUrl() + "/v1/images/generations"))
                    .timeout(Duration.ofMillis(cfg.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            CompletableFuture<HttpResponse<String>> future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response = future.get(cfg.timeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
            String raw = response.body();
            if (response.statusCode() >= 400) {
                throw new BusinessException(502, httpError(response.statusCode(), raw));
            }
            JsonNode data = objectMapper.readTree(raw);
            String url = extractImageUrl(data);
            if (url == null) {
                throw new BusinessException(502, "模型未返回图片");
            }
            return url;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "AI 请求失败: " + e.getMessage());
        }
    }

    // ---------- 解析(静态,便于单测) ----------

    /** OpenAI choices / Gemini candidates / 直返 text|raw */
    static String extractText(JsonNode data) {
        if (data == null || data.isNull()) return null;
        JsonNode choices = data.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isTextual()) return content.asText().trim();
        }
        JsonNode candidates = data.get("candidates");
        if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
            JsonNode parts = candidates.get(0).path("content").path("parts");
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : parts) {
                if (part.has("text")) sb.append(part.get("text").asText()).append('\n');
            }
            String text = sb.toString().trim();
            if (!text.isBlank()) return text;
        }
        if (data.has("text") && data.get("text").isTextual()) return data.get("text").asText().trim();
        if (data.has("raw") && data.get("raw").isTextual()) return data.get("raw").asText().trim();
        return null;
    }

    /** OpenAI data[0].url|b64_json / 直返 url / Gemini inlineData / 文本内嵌 URL */
    static String extractImageUrl(JsonNode data) {
        if (data == null || data.isNull()) return null;
        JsonNode list = data.get("data");
        if (list != null && list.isArray() && !list.isEmpty()) {
            JsonNode item = list.get(0);
            if (item.has("url") && item.get("url").isTextual()) return item.get("url").asText();
            if (item.has("b64_json")) return toDataUrl(item.get("b64_json").asText(), "image/png");
        }
        if (data.has("url") && data.get("url").isTextual()) return data.get("url").asText();
        JsonNode candidates = data.get("candidates");
        if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
            JsonNode parts = candidates.get(0).path("content").path("parts");
            for (JsonNode part : parts) {
                JsonNode inline = part.get("inlineData");
                if (inline != null && inline.has("data")) {
                    String mime = inline.path("mimeType").asText("image/png");
                    return toDataUrl(inline.get("data").asText(), mime);
                }
            }
            for (JsonNode part : parts) {
                if (part.has("text")) {
                    String url = extractImageUrlFromText(part.get("text").asText());
                    if (url != null) return url;
                }
            }
        }
        String raw = data.path("raw").asText(null);
        if (raw != null) {
            return extractImageUrlFromText(raw);
        }
        return null;
    }

    /** 从文本中提取图片 URL:优先带扩展名,否则首个 http(s) URL */
    static String extractImageUrlFromText(String text) {
        if (text == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("https?://[^\\s\"'<>\\]]+", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        String fallback = null;
        while (matcher.find()) {
            String url = matcher.group().trim();
            if (url.endsWith(")")) url = url.substring(0, url.length() - 1);
            if (url.endsWith("]")) url = url.substring(0, url.length() - 1);
            String lower = url.toLowerCase();
            for (String ext : IMAGE_EXTS) {
                if (lower.matches(".*\\." + ext + "(\\?.*)?$")) return url;
            }
            if (fallback == null) fallback = url;
        }
        return fallback;
    }

    private static String toDataUrl(String base64, String mime) {
        return "data:" + mime + ";base64," + base64;
    }

    private static Object imagePart(ObjectMapper om, String src) {
        if (src.startsWith("http://") || src.startsWith("https://")) {
            return om.createObjectNode().set("fileData", om.createObjectNode()
                    .put("fileUri", src).put("mimeType", "image/png"));
        }
        String mime = "image/png";
        String data = src;
        if (src.startsWith("data:")) {
            int comma = src.indexOf(',');
            mime = src.substring(5, comma).split(";")[0];
            data = src.substring(comma + 1);
        }
        return om.createObjectNode().set("inlineData", om.createObjectNode()
                .put("data", data).put("mimeType", mime));
    }

    /** 画幅键 → 生图模型 aspectRatio(不支持 2:3,回退 3:4) */
    static String normalizeAspect(String aspect) {
        if (aspect == null) return null;
        return switch (aspect) {
            case "A4", "2:3", "3:4", "竖版" -> "3:4";
            case "1:1", "正方形" -> "1:1";
            case "16:9", "横版" -> "16:9";
            default -> null;
        };
    }

    private static String httpError(int status, String raw) {
        try {
            var om = new ObjectMapper();
            JsonNode json = om.readTree(raw);
            String message = json.path("error").path("message").asText(null);
            if (message != null && !message.isBlank()) return "AI 接口错误(" + status + "): " + message;
        } catch (Exception ignored) {
            // fall through
        }
        String snippet = raw == null ? "" : raw;
        if (snippet.length() > 300) snippet = snippet.substring(0, 300);
        return "AI 接口错误(" + status + "): " + snippet;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
