package com.echo.infra.embedding;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ApiEmbeddingClient} 单测：注入 mock {@link HttpClient}，<b>不发起真实网络</b>，
 * 覆盖正常响应解析、非 2xx 委托 fallback、异常委托 fallback（照 {@code ApiLlmClient} 优雅降级约定）。
 */
class ApiEmbeddingClientTest {

    private static final EmbeddingConfig CONFIG = EmbeddingConfig.from(k -> switch (k) {
        case "ECHO_EMBED_PROVIDER" -> "qwen";
        case "ECHO_EMBED_API_KEY" -> "sk-test";
        case "ECHO_EMBED_DIM" -> "768";
        default -> null;
    });

    @SuppressWarnings("unchecked")
    private HttpResponse<String> resp(int status, String body) {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        return r;
    }

    @SuppressWarnings("unchecked")
    private HttpClient httpReturning(HttpResponse<String> response) throws IOException, InterruptedException {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        return http;
    }

    @Test
    void parsesEmbeddingFromNormalResponse() throws Exception {
        String body = "{\"data\":[{\"embedding\":[" + "0.25,".repeat(767) + "0.25]}]}";
        HttpClient http = httpReturning(resp(200, body));
        ApiEmbeddingClient client = new ApiEmbeddingClient(
                CONFIG, new MockEmbeddingClient(768), http, Duration.ofSeconds(5));

        float[] v = client.embed("你好");
        assertThat(v).hasSize(768).containsOnly(0.25f);
    }

    @Test
    void non2xxDelegatesToFallback() throws Exception {
        HttpClient http = httpReturning(resp(429, "rate limited"));
        MockEmbeddingClient fallback = new MockEmbeddingClient(768);
        ApiEmbeddingClient client = new ApiEmbeddingClient(CONFIG, fallback, http, Duration.ofSeconds(5));

        float[] v = client.embed("hi");
        // 与 mock 直算一致（确定性伪向量）
        assertThat(v).containsExactly(fallback.embed("hi"));
    }

    @Test
    void exceptionDelegatesToFallback() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("network down"));
        MockEmbeddingClient fallback = new MockEmbeddingClient(768);
        ApiEmbeddingClient client = new ApiEmbeddingClient(CONFIG, fallback, http, Duration.ofSeconds(5));

        float[] v = client.embed("hi");
        assertThat(v).containsExactly(fallback.embed("hi"));
    }

    @Test
    void malformedResultDelegatesToFallback() throws Exception {
        HttpClient http = httpReturning(resp(200, "{\"data\":[]}"));
        MockEmbeddingClient fallback = new MockEmbeddingClient(768);
        ApiEmbeddingClient client = new ApiEmbeddingClient(CONFIG, fallback, http, Duration.ofSeconds(5));

        float[] v = client.embed("hi");
        assertThat(v).containsExactly(fallback.embed("hi"));
    }

    @Test
    void dimensionReflectsConfig() {
        ApiEmbeddingClient client = new ApiEmbeddingClient(CONFIG, new MockEmbeddingClient(768));
        assertThat(client.dimension()).isEqualTo(768);
    }
}
