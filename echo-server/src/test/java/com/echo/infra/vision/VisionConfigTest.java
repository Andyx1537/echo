package com.echo.infra.vision;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VisionConfig} 配置解析与回退判定、{@link VisionClientFactory} 装配单测
 * （不触真实环境变量，用注入的 env 查询函数；照 {@code LlmConfigTest} 写法）。
 */
class VisionConfigTest {

    private VisionConfig of(Map<String, String> env) {
        return VisionConfig.from(env::get);
    }

    @Test
    void defaultsToStubWhenUnset() {
        VisionConfig cfg = of(new HashMap<>());
        assertThat(cfg.provider()).isEqualTo("stub");
        assertThat(cfg.isStub()).isTrue();
    }

    @Test
    void qwenWithoutKeyFallsBackToStub() {
        Map<String, String> env = new HashMap<>();
        env.put("ECHO_VISION_PROVIDER", "qwen");
        VisionConfig cfg = of(env);
        // 未配置 key → 视为 stub（无 key 也能编译/联调）
        assertThat(cfg.isStub()).isTrue();
        // 但 provider 内置默认 baseUrl/model 已解析出来
        assertThat(cfg.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(cfg.model()).isEqualTo("qwen-vl-plus");
    }

    @Test
    void qwenWithKeyIsNotStub() {
        Map<String, String> env = new HashMap<>();
        env.put("ECHO_VISION_PROVIDER", "qwen");
        env.put("ECHO_VISION_API_KEY", "sk-test-123");
        VisionConfig cfg = of(env);
        assertThat(cfg.isStub()).isFalse();
        assertThat(cfg.chatCompletionsUrl())
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
    }

    @Test
    void customBaseUrlTrailingSlashStripped() {
        Map<String, String> env = new HashMap<>();
        env.put("ECHO_VISION_PROVIDER", "qwen");
        env.put("ECHO_VISION_API_KEY", "sk-test");
        env.put("ECHO_VISION_BASE_URL", "https://example.com/v1/");
        env.put("ECHO_VISION_MODEL", "qwen-vl-max");
        VisionConfig cfg = of(env);
        assertThat(cfg.baseUrl()).isEqualTo("https://example.com/v1");
        assertThat(cfg.chatCompletionsUrl()).isEqualTo("https://example.com/v1/chat/completions");
        assertThat(cfg.model()).isEqualTo("qwen-vl-max");
    }

    @Test
    void maxImageEdgeFallsBackToDefaultWhenUnsetOrInvalid() {
        assertThat(of(new HashMap<>()).maxImageEdge()).isEqualTo(ImageCompressor.DEFAULT_MAX_EDGE);

        Map<String, String> bad = new HashMap<>();
        bad.put("ECHO_VISION_MAX_EDGE", "很大");
        assertThat(of(bad).maxImageEdge()).isEqualTo(ImageCompressor.DEFAULT_MAX_EDGE);

        Map<String, String> zero = new HashMap<>();
        zero.put("ECHO_VISION_MAX_EDGE", "0");
        assertThat(of(zero).maxImageEdge()).isEqualTo(ImageCompressor.DEFAULT_MAX_EDGE);

        Map<String, String> ok = new HashMap<>();
        ok.put("ECHO_VISION_MAX_EDGE", "1024");
        assertThat(of(ok).maxImageEdge()).isEqualTo(1024);
    }

    /** 配了资源解析器时，真实分支要套上解析装饰器（否则又会把 resourceId 当 URL 直传）。 */
    @Test
    void factoryWrapsApiClientWithResolver() {
        Map<String, String> env = new HashMap<>();
        env.put("ECHO_VISION_PROVIDER", "qwen");
        env.put("ECHO_VISION_API_KEY", "sk-test");
        IVisionClient client = VisionClientFactory.create(of(env), new StubVisionClient(), id -> "https://x/y.jpg");
        assertThat(client).isInstanceOf(ResolvingVisionClient.class);
    }

    @Test
    void factoryReturnsFallbackForStubConfig() {
        StubVisionClient fallback = new StubVisionClient();
        IVisionClient client = VisionClientFactory.create(of(new HashMap<>()), fallback);
        assertThat(client).isSameAs(fallback);
    }

    @Test
    void factoryReturnsApiClientForRealConfig() {
        Map<String, String> env = new HashMap<>();
        env.put("ECHO_VISION_PROVIDER", "qwen");
        env.put("ECHO_VISION_API_KEY", "sk-test");
        IVisionClient client = VisionClientFactory.create(of(env), new StubVisionClient());
        assertThat(client).isInstanceOf(ApiVisionClient.class);
    }
}
