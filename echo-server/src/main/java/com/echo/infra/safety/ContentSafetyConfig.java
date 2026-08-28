package com.echo.infra.safety;

import java.util.function.Function;

/**
 * 内容安全服务配置（第一关合规词表的外部依赖）。
 *
 * <p>设计对齐 {@link com.echo.infra.vision.VisionConfig} / {@code LlmConfig}：
 * 🔴 <b>key 不入库、不硬编码、不打印明文</b>。</p>
 *
 * <ul>
 *   <li>{@code ECHO_SAFETY_PROVIDER} —— {@code aliyun|tencent|http|none}（默认 none）</li>
 *   <li>{@code ECHO_SAFETY_ENDPOINT} —— 检测端点全 URL</li>
 *   <li>{@code ECHO_SAFETY_APP_ID} / {@code ECHO_SAFETY_API_KEY} —— 凭据</li>
 *   <li>{@code ECHO_SAFETY_REGION} —— 服务地域（厂商必填项）</li>
 *   <li>{@code ECHO_SAFETY_TIMEOUT_MS} —— 单次调用超时（默认 800ms）</li>
 *   <li>{@code ECHO_SAFETY_QPS} —— 本进程限流（默认 50/s，0 = 不限）</li>
 *   <li>{@code ECHO_SAFETY_BREAKER_THRESHOLD} —— 连续失败多少次后进入熔断（默认 5）</li>
 *   <li>{@code ECHO_SAFETY_BREAKER_COOLDOWN_MS} —— 熔断冷却（默认 30s）</li>
 * </ul>
 *
 * <p>⚠️ 超时默认给 800ms 而不是更长：这一关在<b>投递前</b>的同步路径上，
 * 超时长会直接变成用户等待。而超时的后果是「按未通过处理」——宁可让一条回声回落兜底，
 * 不可让用户对着转圈等三秒。</p>
 */
public record ContentSafetyConfig(String provider, String endpoint, String appId, String apiKey,
                                  String region, int timeoutMs, int qps,
                                  int breakerThreshold, long breakerCooldownMs) {

    public static final String PROVIDER_NONE = "none";

    /**
     * ⚠️ 联调 / 单测用的假实现。<b>不是合规能力</b>——生产用它等于没有第一关。
     * {@link ContentSafetyFactory} 装配它时会打 warn。
     */
    public static final String PROVIDER_FAKE = "fake";

    public static ContentSafetyConfig fromEnv() {
        return from(System::getenv);
    }

    /** 从任意「键 → 值」查询函数装配（便于单测注入，不触真实环境变量）。 */
    public static ContentSafetyConfig from(Function<String, String> env) {
        String provider = blankToNull(env.apply("ECHO_SAFETY_PROVIDER"));
        provider = provider == null ? PROVIDER_NONE : provider.trim().toLowerCase();
        return new ContentSafetyConfig(
                provider,
                blankToNull(env.apply("ECHO_SAFETY_ENDPOINT")),
                blankToNull(env.apply("ECHO_SAFETY_APP_ID")),
                blankToNull(env.apply("ECHO_SAFETY_API_KEY")),
                blankToNull(env.apply("ECHO_SAFETY_REGION")),
                intOr(env.apply("ECHO_SAFETY_TIMEOUT_MS"), 800),
                intOr(env.apply("ECHO_SAFETY_QPS"), 50),
                intOr(env.apply("ECHO_SAFETY_BREAKER_THRESHOLD"), 5),
                intOr(env.apply("ECHO_SAFETY_BREAKER_COOLDOWN_MS"), 30_000));
    }

    /** 用于单测的空配置（provider=none）。 */
    public static ContentSafetyConfig unconfigured() {
        return new ContentSafetyConfig(PROVIDER_NONE, null, null, null, null,
                800, 50, 5, 30_000);
    }

    /**
     * 联调 / 单测配置（provider=fake，不需要端点与凭据）。
     *
     * <p>{@code qps=0}（不限）：单测里一秒内会打很多次检测，本地配额会把它们变成
     * {@code LOCAL_QUOTA} 失败，而那不是用例想验的东西。</p>
     */
    public static ContentSafetyConfig fake() {
        return new ContentSafetyConfig(PROVIDER_FAKE, null, null, null, null,
                800, 0, 5, 30_000);
    }

    public boolean isFake() {
        return PROVIDER_FAKE.equals(provider);
    }

    /**
     * 是否配置齐全到<b>可以尝试真实调用</b>。
     *
     * <p>🔴 注意这只是「配置齐了」，<b>不等于就绪</b>。就绪的判据是
     * {@link ContentSafetyGate#isOperational()} —— 真的调通过一次。
     * 两者分开是本模块最要紧的一处区分：配置齐全但调不通时，
     * 能力必须仍然算未就绪，否则 {@code S13} 的前置校验会被一份填错的配置骗过去。</p>
     */
    public boolean isConfigured() {
        if (provider == null || PROVIDER_NONE.equals(provider)) {
            return false;
        }
        if (isFake()) {
            // ⚠️ 假实现不需要端点与凭据。它「已配置」，但它不是合规能力——见 PROVIDER_FAKE
            return true;
        }
        return endpoint != null && apiKey != null;
    }

    private static int intOr(String raw, int fallback) {
        String v = blankToNull(raw);
        if (v == null) {
            return fallback;
        }
        try {
            int n = Integer.parseInt(v);
            return n >= 0 ? n : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 空白归一为 null 并 trim（env 文件为 CRLF 时值会带尾随 \r，会污染 URL 与鉴权头）。 */
    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
