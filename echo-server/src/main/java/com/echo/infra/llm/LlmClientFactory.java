package com.echo.infra.llm;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link ILlmClient} 装配工厂：按 {@link LlmConfig}（{@code ECHO_LLM_PROVIDER} 等 env）选实现。
 *
 * <p>回退策略（任务约束 §1.3）：未配置 key 或 {@code provider=mock} 时回落 {@link MockLlmClient}，
 * 保证无 key 也能编译、跑测试、本地联调；配置了真实供应商时用 {@link ApiLlmClient}，并以 mock 作为
 * 网络异常时的兜底委托。</p>
 */
@Slf4j
public final class LlmClientFactory {

    private LlmClientFactory() {
    }

    /** 从环境变量装配。 */
    public static ILlmClient fromEnv() {
        return create(LlmConfig.fromEnv(), new MockLlmClient());
    }

    /**
     * 按配置选实现。
     *
     * @param config   LLM 配置
     * @param fallback 回退实现（既作 mock 分支的返回，也作 Api 分支的网络兜底委托）；null 时新建 {@link MockLlmClient}
     */
    public static ILlmClient create(LlmConfig config, ILlmClient fallback) {
        ILlmClient safeFallback = fallback != null ? fallback : new MockLlmClient();
        if (config.isMock()) {
            log.info("LLM 装配：provider={} 无有效 key/端点，回落 MockLlmClient（无 key 也可联调）。", config.provider());
            return safeFallback;
        }
        log.info("LLM 装配：provider={}, model={}, baseUrl={}（真实 OpenAI 兼容端点，key 走 env 不入库）。",
                config.provider(), config.model(), config.baseUrl());
        return new ApiLlmClient(config, safeFallback);
    }
}
