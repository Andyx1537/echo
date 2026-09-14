package com.echo.infra.imagegen;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImageGenConfigTest {
    @Test
    void missingProviderFallsBackToMock() {
        ImageGenConfig config = ImageGenConfig.from(key -> null);
        assertThat(config.isMock()).isTrue();
        assertThat(config.workspaceBase()).isEqualTo("https://dashscope.aliyuncs.com");
    }

    @Test
    void wanxUsesLlmKeyAndWorkspaceHost() {
        Map<String, String> env = new HashMap<>();
        env.put("ECHO_IMGGEN_PROVIDER", "wanx");
        env.put("ECHO_LLM_API_KEY", "sk-test");
        env.put("ECHO_LLM_BASE_URL", "https://ws-demo.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");
        ImageGenConfig config = ImageGenConfig.from(env::get);
        assertThat(config.isMock()).isFalse();
        assertThat(config.workspaceBase()).isEqualTo("https://ws-demo.cn-beijing.maas.aliyuncs.com");
        assertThat(config.imageSynthesisUrl()).endsWith("/api/v1/services/aigc/image2image/image-synthesis");
        assertThat(config.apiKey()).isEqualTo("sk-test");
    }
}
