package com.echo.infra.imagegen;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiImageGenClientTest {
    private static final ImageGenConfig CONFIG = ImageGenConfig.from(key -> switch (key) {
        case "ECHO_IMGGEN_PROVIDER" -> "wanx";
        case "ECHO_IMGGEN_API_KEY" -> "sk-test";
        default -> null;
    });

    @SuppressWarnings("unchecked")
    private HttpResponse<String> resp(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    @Test
    void pollsUntilThreeUrlsArrive() throws Exception {
        HttpResponse<String> submitted = resp(200, "{\"output\":{\"task_id\":\"t1\",\"task_status\":\"PENDING\"}}");
        HttpResponse<String> done = resp(200, "{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":["
                + "{\"url\":\"https://cdn.example/a.png\"},"
                + "{\"url\":\"https://cdn.example/b.png\"},"
                + "{\"url\":\"https://cdn.example/c.png\"}]}}");
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(submitted, done);
        ApiImageGenClient client = new ApiImageGenClient(CONFIG, http, Duration.ofSeconds(2));
        List<GeneratedImage> images = client.stylize("data:image/jpeg;base64,abc");
        assertThat(images).extracting(GeneratedImage::url)
                .containsExactly("https://cdn.example/a.png", "https://cdn.example/b.png", "https://cdn.example/c.png");
    }

    @Test
    void failedTaskIsVisible() throws Exception {
        HttpResponse<String> submitted = resp(200, "{\"output\":{\"task_id\":\"t1\"}}");
        HttpResponse<String> failed = resp(200, "{\"output\":{\"task_status\":\"FAILED\"},\"message\":\"quota\"}");
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(submitted, failed);
        ApiImageGenClient client = new ApiImageGenClient(CONFIG, http, Duration.ofSeconds(2));
        assertThatThrownBy(() -> client.stylize("https://img.example/pet.jpg"))
                .isInstanceOf(ImageGenException.class)
                .hasMessageContaining("quota");
    }

    @Test
    void factoryStaysMockWithoutKey() {
        assertThat(ImageGenClientFactory.create(ImageGenConfig.from(Map.<String, String>of()::get)))
                .isInstanceOf(MockImageGenClient.class);
    }
}
