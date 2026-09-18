package com.aimanga.v2.ai;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.service.ConfigService;
import com.aimanga.v2.storage.StorageService;
import com.aimanga.v2.task.RedisConcurrencyLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiServiceTest {

    private MockWebServer server;
    private ConfigService configService;
    private StorageService storageService;
    private AiService aiService;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        configService = mock(ConfigService.class);
        storageService = mock(StorageService.class);
        RedisConcurrencyLimiter limiter = mock(RedisConcurrencyLimiter.class);
        when(limiter.acquireAi(anyString())).thenReturn("permit-token");
        aiService = new AiService(configService, new AiClient(new ObjectMapper()), storageService, new ObjectMapper(), limiter);
        when(configService.getString(anyString())).thenAnswer(inv -> switch (inv.getArgument(0, String.class)) {
            case "ai_text_api_url", "ai_image_api_url" -> server.url("/").toString();
            case "ai_text_api_key", "ai_image_api_key" -> "sk-test";
            case "ai_text_model", "ai_image_model" -> "gemini-2.5-flash";
            case "ai_text_timeout", "ai_image_timeout" -> "5000";
            default -> "";
        });
        // mock 默认 getInt 返回 0 → HttpRequest timeout PT0S 非法,这里桩为 5000ms
        when(configService.getInt(anyString(), anyInt())).thenReturn(5000);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    record ChapterItem(int chapterNo, String title) {}

    record ChapterSplitResult(java.util.List<ChapterItem> chapters) {}

    @Test
    void chatJson_retriesOnceOnBadJsonThenSucceeds() {
        // 第一次:裸文本(解析失败触发重试)
        server.enqueue(new MockResponse().setBody("这不是 JSON"));
        // 第二次:模型把 JSON 作为文本返回(带代码围栏,验证围栏剥离)
        server.enqueue(new MockResponse().setBody("""
                {"candidates":[{"content":{"parts":[{"text":"```json\\n{\\"chapters\\":[{\\"chapterNo\\":1,\\"title\\":\\"第1话\\"}]}\\n```"}]}}]}
                """));

        ChapterSplitResult result = aiService.chatJson("text", "拆话测试", ChapterSplitResult.class);
        assertThat(result.chapters()).hasSize(1);
        assertThat(result.chapters().get(0).title()).isEqualTo("第1话");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void generateImage_savesGeminiInlineBase64ToOss() {
        server.enqueue(new MockResponse().setBody("""
                {"candidates":[{"content":{"parts":[
                    {"inlineData":{"mimeType":"image/png","data":"QUJD"}}
                ]}}]}
                """));
        when(storageService.saveImage(anyString(), anyLong(), any(), anyString()))
                .thenReturn("https://bucket.example.com/pages/1/20260101/1_abc.png");

        String url = aiService.generateImage("image", "测试", List.of(), "3:4", 7L);

        assertThat(url).isEqualTo("https://bucket.example.com/pages/1/20260101/1_abc.png");
        Mockito.verify(storageService).saveImage(eq("pages"), eq(7L), any(byte[].class), eq("png"));
    }

    @Test
    void generateImage_transfersVendorUrlToOss() {
        server.enqueue(new MockResponse().setBody("""
                {"data":[{"url":"https://vendor.example.com/img/1.png"}]}
                """));
        when(storageService.saveImageFromUrl(anyString(), anyLong(), anyString()))
                .thenReturn("https://bucket.example.com/pages/1/20260101/2_def.png");

        String url = aiService.generateImage("image", "测试", List.of(), "3:4", 7L);

        assertThat(url).isEqualTo("https://bucket.example.com/pages/1/20260101/2_def.png");
        Mockito.verify(storageService).saveImageFromUrl(eq("pages"), eq(7L), eq("https://vendor.example.com/img/1.png"));
    }

    @Test
    void generateImage_failsWhenNoImageReturned() {
        // 两次都返回"无图文本",验证自动重试 1 次后仍抛业务异常
        server.enqueue(new MockResponse().setBody(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"抱歉,无法生成\"}]}}]}"));
        server.enqueue(new MockResponse().setBody(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"还是无法生成\"}]}}]}"));
        assertThatThrownBy(() -> aiService.generateImage("image", "测试", List.of(), "3:4", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模型未返回图片");
        assertThat(server.getRequestCount()).isEqualTo(2); // 自动重试 1 次
    }
}
