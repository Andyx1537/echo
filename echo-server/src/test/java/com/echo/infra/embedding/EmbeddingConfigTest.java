package com.echo.infra.embedding;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EmbeddingConfig} 配置解析与回退判定、{@link EmbeddingClientFactory} 装配单测
 * （不触真实环境变量，用注入的 env 查询函数；照 {@code LlmConfigTest} 写法）。
 */
class EmbeddingConfigTest {

    private EmbeddingConfig of(Map<String, String> env) {
        return EmbeddingConfig.from(env::get);
    }

    @Test
    void defaultsToMockWhenUnset() {
        EmbeddingConfig cfg = of(new HashMap<>());
        assertThat(cfg.provider()).isEqualTo("mock");
        assertThat(cfg.isMock()).isTrue();
        assertThat(cfg.dimensions()).isEqualTo(IEmbeddingClient.DEFAULT_DIM);
    }

    @Test
    void qwenWithoutKeyFallsBackToMock() {
        Map<String, String> env = new HashMap<>();
        env.put("ECHO_EMBED_PROVIDER", "qwen");
        EmbeddingConfig cfg = of(env);
        assertThat(cfg.isMock()).isTrue();
        assertThat(cfg.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(cfg.model()).isEqualTo("text-embedding-v3");
    }

    @Test
    void qwenWithKeyIsNotMock() {
        Map<String, String> env = new HashMap<>();
        env.put("ECHO_EMBED_PROVIDER", "qwen");
        env.put("ECHO_EMBED_API_KEY", "sk-test");
        EmbeddingConfig cfg = of(env);
        assertThat(cfg.isMock()).isFalse();
        assertThat(cfg.embeddingsUrl())
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings");
    }

    @Test
    void customDimAndBaseUrlParsed() {
        Map<String, String> env = new HashMap<>();
        env.put("ECHO_EMBED_PROVIDER", "qwen");
        env.put("ECHO_EMBED_API_KEY", "sk-test");
        env.put("ECHO_EMBED_BASE_URL", "https://example.com/v1/");
        env.put("ECHO_EMBED_DIM", "1024");
        EmbeddingConfig cfg = of(env);
        assertThat(cfg.baseUrl()).isEqualTo("https://example.com/v1");
        assertThat(cfg.dimensions()).isEqualTo(1024);
    }

    @Test
    void badDimFallsBackToDefault() {
        Map<String, String> env = new HashMap<>();
        env.put("ECHO_EMBED_DIM", "not-a-number");
        assertThat(of(env).dimensions()).isEqualTo(IEmbeddingClient.DEFAULT_DIM);
    }

    @Test
    void factoryReturnsFallbackForMockConfig() {
        MockEmbeddingClient fallback = new MockEmbeddingClient();
        IEmbeddingClient client = EmbeddingClientFactory.create(of(new HashMap<>()), fallback);
        assertThat(client).isSameAs(fallback);
    }

    @Test
    void factoryReturnsApiClientForRealConfig() {
        Map<String, String> env = new HashMap<>();
        env.put("ECHO_EMBED_PROVIDER", "qwen");
        env.put("ECHO_EMBED_API_KEY", "sk-test");
        IEmbeddingClient client = EmbeddingClientFactory.create(of(env), new MockEmbeddingClient());
        assertThat(client).isInstanceOf(ApiEmbeddingClient.class);
    }
}
