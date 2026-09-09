package com.aimanga.v2.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiClientTest {

    private MockWebServer server;
    private AiClient client = new AiClient(new ObjectMapper());
    private ChatConfig cfg;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        cfg = new ChatConfig(server.url("/").toString(), "sk-test", "gemini-2.5-flash", 5000);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void chatText_parsesGeminiCandidates() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"candidates":[{"content":{"parts":[{"text":"你好"}]}}]}
                """));
        String text = client.chatText(cfg, "ping", List.of());
        assertThat(text).isEqualTo("你好");
        var recorded = server.takeRequest();
        assertThat(recorded.getPath()).contains("/v1beta/models/gemini-2.5-flash:generateContent");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-test");
        assertThat(recorded.getBody().readUtf8()).contains("ping");
    }

    @Test
    void chatText_parsesOpenAiChoices() {
        server.enqueue(new MockResponse().setBody("""
                {"choices":[{"message":{"content":"pong"}}]}
                """));
        assertThat(client.chatText(cfg, "ping", List.of())).isEqualTo("pong");
    }

    @Test
    void chatText_parsesRawNonJsonBody() {
        server.enqueue(new MockResponse().setBody("直接返回的文本"));
        assertThat(client.chatText(cfg, "ping", List.of())).isEqualTo("直接返回的文本");
    }

    @Test
    void extractImageUrl_openAiDataUrl() throws IOException {
        var node = new ObjectMapper().readTree("""
                {"data":[{"url":"https://cdn.example.com/a.png?x=1"}]}
                """);
        assertThat(AiClient.extractImageUrl(node)).isEqualTo("https://cdn.example.com/a.png?x=1");
    }

    @Test
    void extractImageUrl_geminiInlineBecomesDataUrl() throws IOException {
        var node = new ObjectMapper().readTree("""
                {"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"QUJD"}}]}}]}
                """);
        assertThat(AiClient.extractImageUrl(node)).isEqualTo("data:image/png;base64,QUJD");
    }

    @Test
    void extractImageUrl_fallsBackToUrlInText() throws IOException {
        var node = new ObjectMapper().readTree("""
                {"candidates":[{"content":{"parts":[{"text":"生成完成 https://gw.example.com/img/abc?token=1 请查收"}]}}]}
                """);
        assertThat(AiClient.extractImageUrl(node)).isEqualTo("https://gw.example.com/img/abc?token=1");
    }

    @Test
    void normalizeAspect_maps2to3To3to4() {
        assertThat(AiClient.normalizeAspect("2:3")).isEqualTo("3:4");
        assertThat(AiClient.normalizeAspect("1:1")).isEqualTo("1:1");
        assertThat(AiClient.normalizeAspect("横版")).isEqualTo("16:9");
        assertThat(AiClient.normalizeAspect("unknown")).isNull();
    }
}
