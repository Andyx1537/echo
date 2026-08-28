package com.echo.infra.safety;

import lombok.extern.slf4j.Slf4j;

/**
 * 第一关（合规词表）的装配工厂：按 {@code ECHO_SAFETY_*} env 选客户端实现。
 *
 * <p>与 {@link com.echo.infra.vision.VisionClientFactory} / {@code LlmClientFactory} 同形，
 * 但有一处<b>刻意的不同</b>：🔴 <b>未配置时不回落任何"桩实现"。</b></p>
 *
 * <p>视觉与 LLM 的桩回落是安全的——桩返回中性默认，最坏结果是内容平庸。安全闸的桩回落
 * 不是这样：一个"总是通过"的桩会让第一关在代码上看起来已实现，而实际什么都没查，
 * 且 {@link ContentSafetyGate#isOperational()} 会变成 true，于是 {@code S13} 开关能被打开 ——
 * 那正是「治理能力未就绪却敞开自由文本入口」。</p>
 *
 * <p>所以未配置时返回的是一个 {@code provider=none} 的闸门：它对每次检测返回
 * {@link ContentSafetyVerdict.Outcome#SKIPPED_UNCONFIGURED}（跳过，不是通过），
 * 并且 {@code isOperational()} 恒 false。</p>
 */
@Slf4j
public final class ContentSafetyFactory {

    private ContentSafetyFactory() {
    }

    /** 从环境变量装配并做一次启动期探活。 */
    public static ContentSafetyGate fromEnv() {
        return create(ContentSafetyConfig.fromEnv());
    }

    /**
     * 按配置装配。装配后立刻 {@link ContentSafetyGate#probe() 探活}，
     * 好让「配置填了但调不通」在启动日志里就暴露，而不是等第一条用户文本进来才发现。
     */
    public static ContentSafetyGate create(ContentSafetyConfig config) {
        ContentSafetyGate gate = new ContentSafetyGate(config, clientOf(config));
        gate.probe();
        return gate;
    }

    /**
     * ⚠️ 未配置时返回的客户端<b>永远不会被调用</b>——{@link ContentSafetyGate#inspect} 在
     * {@code !isConfigured()} 时直接返回「跳过」，不走到客户端。这里给一个必抛的实现而不是 null，
     * 是为了让「万一将来有人改了那个判断」的后果是<b>抛异常（被收成未通过）</b>，
     * 而不是 NPE 沿栈上抛被某个宽泛的 catch 变成「这条内容没问题」。
     */
    private static IContentSafetyClient clientOf(ContentSafetyConfig config) {
        if (config.isFake()) {
            log.warn("🔴 内容安全装配为假实现（ECHO_SAFETY_PROVIDER=fake）：仅供联调，"
                    + "生产用它等于没有第一关");
            return new FakeContentSafetyClient();
        }
        if (!config.isConfigured()) {
            log.warn("内容安全未配置（ECHO_SAFETY_PROVIDER={}）：第一关合规词表无拦截能力，"
                    + "S13 留一句话开关将无法打开", config.provider());
            return new IContentSafetyClient() {
                @Override
                public ContentSafetyVerdict inspectText(String text) throws ContentSafetyException {
                    throw new ContentSafetyException(ContentSafetyException.Reason.TRANSPORT,
                            "内容安全服务未配置");
                }

                @Override
                public String provider() {
                    return ContentSafetyConfig.PROVIDER_NONE;
                }
            };
        }
        log.info("内容安全装配：provider={}, endpoint={}, timeout={}ms, qps={}（key 走 env 不入库）",
                config.provider(), config.endpoint(), config.timeoutMs(), config.qps());
        return new HttpContentSafetyClient(config);
    }
}
