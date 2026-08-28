package com.echo.infra.llm;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LlmConfig} 配置解析与回退判定单测（不触真实环境变量，用注入的 env 查询函数）。
 */
class LlmConfigTest {

    private LlmConfig of(Map<String, String> env) {
        return LlmConfig.from(env::get);
    }

    @Test
    void defaultsToMockWhenUnset() {
        LlmConfig cfg = of(new HashMap<>());
        assertThat(cfg.provider()).isEqualTo("mock");
        assertThat(cfg.isMock()).isTrue();
        assertThat(cfg.temperature()).isEqualTo(LlmConfig.DEFAULT_TEMPERATURE);
    }

    @Test
    void providerWithoutKeyFallsBackToMock() {
        Map<String, String> env = new HashMap<>();
        env.put("ECHO_LLM_PROVIDER", "deepseek");
        LlmConfig cfg = of(env);
        // 未配置 key → 视为 mock（无 key 也能编译/联调）
        assertThat(cfg.isMock()).isTrue();
        // 但 provider 内置默认 baseUrl/model 已解析出来
        assertThat(cfg.baseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(cfg.model()).isEqualTo("deepseek-chat");
    }

    @Test
    void realProviderWithKeyIsNotMock() {
        Map<String, String> env = new HashMap<>();
        env.put("ECHO_LLM_PROVIDER", "doubao");
        env.put("ECHO_LLM_API_KEY", "sk-test-123");
        env.put("ECHO_LLM_MODEL", "ep-2026-xyz");
        env.put("ECHO_LLM_TEMPERATURE", "0.5");
        LlmConfig cfg = of(env);
        assertThat(cfg.isMock()).isFalse();
        assertThat(cfg.baseUrl()).isEqualTo("https://ark.cn-beijing.volces.com/api/v3");
        assertThat(cfg.model()).isEqualTo("ep-2026-xyz");
        assertThat(cfg.temperature()).isEqualTo(0.5);
        assertThat(cfg.chatCompletionsUrl()).endsWith("/chat/completions");
    }

    @Test
    void localProviderNeedsNoKey() {
        Map<String, String> env = new HashMap<>();
        env.put("ECHO_LLM_PROVIDER", "local");
        env.put("ECHO_LLM_BASE_URL", "http://127.0.0.1:11434/v1/");
        LlmConfig cfg = of(env);
        assertThat(cfg.isMock()).isFalse();
        // 末尾斜杠被裁掉，端点拼接正确
        assertThat(cfg.baseUrl()).isEqualTo("http://127.0.0.1:11434/v1");
        assertThat(cfg.chatCompletionsUrl()).isEqualTo("http://127.0.0.1:11434/v1/chat/completions");
    }

    @Test
    void factoryReturnsFallbackForMockConfig() {
        MockLlmClient fallback = new MockLlmClient();
        ILlmClient client = LlmClientFactory.create(of(new HashMap<>()), fallback);
        assertThat(client).isSameAs(fallback);
    }

    @Test
    void factoryReturnsApiClientForRealConfig() {
        Map<String, String> env = new HashMap<>();
        env.put("ECHO_LLM_PROVIDER", "qwen");
        env.put("ECHO_LLM_API_KEY", "sk-test");
        ILlmClient client = LlmClientFactory.create(of(env), new MockLlmClient());
        assertThat(client).isInstanceOf(ApiLlmClient.class);
    }

    @Test
    void badTemperatureFallsBackToDefault() {
        Map<String, String> env = new HashMap<>();
        env.put("ECHO_LLM_TEMPERATURE", "not-a-number");
        assertThat(of(env).temperature()).isEqualTo(LlmConfig.DEFAULT_TEMPERATURE);
    }
}
