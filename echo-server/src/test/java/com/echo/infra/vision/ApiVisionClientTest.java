package com.echo.infra.vision;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ApiVisionClient} 单测：注入 mock {@link HttpClient}，<b>不发起真实网络</b>，
 * 覆盖正常响应解析、非 2xx 委托 fallback、异常委托 fallback（照 {@code ApiLlmClient} 优雅降级约定）。
 */
class ApiVisionClientTest {

    private static final VisionConfig CONFIG = VisionConfig.from(k -> switch (k) {
        case "ECHO_VISION_PROVIDER" -> "qwen";
        case "ECHO_VISION_API_KEY" -> "sk-test";
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
    void parsesSubjectsFromNormalResponse() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                + "\"{\\\"subjects\\\":[{\\\"species\\\":\\\"猫\\\",\\\"confidence\\\":0.6},"
                + "{\\\"species\\\":\\\"狗\\\",\\\"confidence\\\":0.92}]}\"}}]}";
        HttpClient http = httpReturning(resp(200, body));
        ApiVisionClient client = new ApiVisionClient(CONFIG, new StubVisionClient(), http, Duration.ofSeconds(5));

        List<DetectSubject> subjects = client.detect("https://img.example.com/pet.jpg");

        // 按 confidence 降序：狗(0.92) 在前
        assertThat(subjects).hasSize(2);
        assertThat(subjects.get(0).species()).isEqualTo("狗");
        assertThat(subjects.get(0).confidence()).isEqualTo(0.92);
        assertThat(subjects.get(0).subjectType()).isEqualTo(DetectSubject.SubjectType.ANIMAL);
        assertThat(subjects.get(1).species()).isEqualTo("猫");
    }

    @Test
    void unknownSpeciesDegradesToOther() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"subjects\\\":[{\\\"species\\\":\\\"外星生物\\\",\\\"confidence\\\":0.7}]}\"}}]}";
        HttpClient http = httpReturning(resp(200, body));
        ApiVisionClient client = new ApiVisionClient(CONFIG, new StubVisionClient(), http, Duration.ofSeconds(5));

        List<DetectSubject> subjects = client.detect("res-1");
        assertThat(subjects).hasSize(1);
        assertThat(subjects.get(0).species()).isEqualTo("其他");
    }

    @Test
    void parsesContentWrappedInCodeFence() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"content\":"
                + "\"```json\\n{\\\"subjects\\\":[{\\\"species\\\":\\\"兔\\\",\\\"confidence\\\":0.8}]}\\n```\"}}]}";
        HttpClient http = httpReturning(resp(200, body));
        ApiVisionClient client = new ApiVisionClient(CONFIG, new StubVisionClient(), http, Duration.ofSeconds(5));

        List<DetectSubject> subjects = client.detect("res-1");
        assertThat(subjects).hasSize(1);
        assertThat(subjects.get(0).species()).isEqualTo("兔");
    }

    @Test
    void nonZeroConfidenceIsClamped() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"subjects\\\":[{\\\"species\\\":\\\"狗\\\",\\\"confidence\\\":3.5}]}\"}}]}";
        HttpClient http = httpReturning(resp(200, body));
        ApiVisionClient client = new ApiVisionClient(CONFIG, new StubVisionClient(), http, Duration.ofSeconds(5));

        List<DetectSubject> subjects = client.detect("res-1");
        assertThat(subjects.get(0).confidence()).isEqualTo(1.0);
    }

    @Test
    void non2xxDelegatesToFallback() throws Exception {
        HttpClient http = httpReturning(resp(500, "boom"));
        // fallback 为单主体桩：animal/狗/0.5
        ApiVisionClient client = new ApiVisionClient(CONFIG, new StubVisionClient(false), http, Duration.ofSeconds(5));

        List<DetectSubject> subjects = client.detect("res-1");
        assertThat(subjects).hasSize(1);
        assertThat(subjects.get(0).species()).isEqualTo("狗");
        assertThat(subjects.get(0).confidence()).isEqualTo(0.5);
    }

    @Test
    void exceptionDelegatesToFallback() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("network down"));
        ApiVisionClient client = new ApiVisionClient(CONFIG, new StubVisionClient(false), http, Duration.ofSeconds(5));

        List<DetectSubject> subjects = client.detect("res-1");
        assertThat(subjects).hasSize(1);
        assertThat(subjects.get(0).species()).isEqualTo("狗");
    }

    /** 诚实标识：模型真识别到 → source=model。 */
    @Test
    void modelResultIsMarkedAsModelSource() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"subjects\\\":[{\\\"species\\\":\\\"猫\\\",\\\"confidence\\\":0.98}]}\"}}]}";
        HttpClient http = httpReturning(resp(200, body));
        ApiVisionClient client = new ApiVisionClient(CONFIG, new StubVisionClient(false), http, Duration.ofSeconds(5));

        DetectResult result = client.detectWithSource("data:image/jpeg;base64,AAAA");

        assertThat(result.source()).isEqualTo(DetectResult.Source.MODEL);
        assertThat(result.subjects().get(0).species()).isEqualTo("猫");
    }

    /** 诚实标识：非 2xx 兜底 → source=fallback，前端据此不把「狗」当识别结果展示。 */
    @Test
    void fallbackResultIsMarkedAsFallbackSource() throws Exception {
        HttpClient http = httpReturning(resp(400, "{\"error\":{\"message\":\"InvalidParameter\"}}"));
        ApiVisionClient client = new ApiVisionClient(CONFIG, new StubVisionClient(false), http, Duration.ofSeconds(5));

        DetectResult result = client.detectWithSource("res-1");

        assertThat(result.source()).isEqualTo(DetectResult.Source.FALLBACK);
        assertThat(result.subjects()).hasSize(1);
        assertThat(result.subjects().get(0).species()).isEqualTo("狗");
    }

    @Test
    void emptyOrMalformedResultDelegatesToFallback() throws Exception {
        // choices 缺失 → content 为 null → 委托 fallback
        HttpClient http = httpReturning(resp(200, "{\"unexpected\":true}"));
        ApiVisionClient client = new ApiVisionClient(CONFIG, new StubVisionClient(false), http, Duration.ofSeconds(5));

        List<DetectSubject> subjects = client.detect("res-1");
        assertThat(subjects).hasSize(1);
        assertThat(subjects.get(0).species()).isEqualTo("狗");
    }
}
