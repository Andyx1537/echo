package com.echo.infra.embedding;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link IEmbeddingClient} 装配工厂：按 {@link EmbeddingConfig}（{@code ECHO_EMBED_PROVIDER} 等 env）选实现。
 *
 * <p>回退策略（对齐 {@link com.echo.infra.llm.LlmClientFactory}）：未配置 key 或 {@code provider=mock}
 * 时回落 {@link MockEmbeddingClient}，保证无 key 也能编译、跑测试、本地联调（默认仍走 mock，不破坏现有行为）；
 * 配置了真实供应商时用 {@link ApiEmbeddingClient}，并以 mock 作为网络异常时的兜底委托。</p>
 */
@Slf4j
public final class EmbeddingClientFactory {

    private EmbeddingClientFactory() {
    }

    /** 从环境变量装配。 */
    public static IEmbeddingClient fromEnv() {
        EmbeddingConfig config = EmbeddingConfig.fromEnv();
        return create(config, new MockEmbeddingClient(config.dimensions()));
    }

    /**
     * 按配置选实现。
     *
     * @param config   嵌入配置
     * @param fallback 回退实现（既作 mock 分支的返回，也作 Api 分支的网络兜底委托）；null 时新建 {@link MockEmbeddingClient}
     */
    public static IEmbeddingClient create(EmbeddingConfig config, IEmbeddingClient fallback) {
        IEmbeddingClient safeFallback = fallback != null ? fallback : new MockEmbeddingClient(config.dimensions());
        if (config.isMock()) {
            log.info("Embedding 装配：provider={} 无有效 key/端点，回落 MockEmbeddingClient（无 key 也可联调）。",
                    config.provider());
            return safeFallback;
        }
        log.info("Embedding 装配：provider={}, model={}, baseUrl={}, dim={}（真实 OpenAI 兼容端点，key 走 env 不入库）。",
                config.provider(), config.model(), config.baseUrl(), config.dimensions());
        return new ApiEmbeddingClient(config, safeFallback);
    }
}
