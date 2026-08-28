package com.echo.infra.llm;

import java.util.function.Function;

/**
 * LLM 供应商配置（AI-CAPABILITIES §2 配置化）。
 *
 * <p>从环境变量注入，<b>API key 不入库、不硬编码</b>：</p>
 * <ul>
 *   <li>{@code ECHO_LLM_PROVIDER} —— doubao|deepseek|qwen|openai|local|mock（默认 mock）</li>
 *   <li>{@code ECHO_LLM_BASE_URL} —— OpenAI 兼容端点根（缺省时按 provider 取内置默认）</li>
 *   <li>{@code ECHO_LLM_API_KEY} —— 密钥（缺省即回落 mock，保证无 key 也能编译/联调）</li>
 *   <li>{@code ECHO_LLM_MODEL} —— 模型名/推理接入点（缺省时按 provider 取内置默认）</li>
 *   <li>{@code ECHO_LLM_TEMPERATURE} —— 采样温度（缺省 0.8）</li>
 * </ul>
 *
 * <p>豆包/DeepSeek/Qwen 均提供 OpenAI 兼容的 Chat Completions 端点，故只需一套
 * {@link ApiLlmClient} 走 {@code {baseUrl}/chat/completions} 即可覆盖多家。</p>
 */
public record LlmConfig(String provider, String baseUrl, String apiKey, String model, double temperature) {

    /** 采样温度缺省值（与 COPY-GUIDE 基调匹配：略有灵动但不失控）。 */
    public static final double DEFAULT_TEMPERATURE = 0.8;

    /** 从进程环境变量装配。 */
    public static LlmConfig fromEnv() {
        return from(System::getenv);
    }

    /**
     * 从任意「键 → 值」查询函数装配（便于单测注入，不触真实环境变量）。
     *
     * @param env 形如 {@code key -> value} 的查询函数，未命中返回 {@code null}
     */
    public static LlmConfig from(Function<String, String> env) {
        String provider = blankToNull(env.apply("ECHO_LLM_PROVIDER"));
        provider = provider == null ? "mock" : provider.trim().toLowerCase();
        String apiKey = blankToNull(env.apply("ECHO_LLM_API_KEY"));
        String baseUrl = blankToNull(env.apply("ECHO_LLM_BASE_URL"));
        if (baseUrl == null) {
            baseUrl = defaultBaseUrl(provider);
        } else {
            baseUrl = stripTrailingSlash(baseUrl.trim());
        }
        String model = blankToNull(env.apply("ECHO_LLM_MODEL"));
        if (model == null) {
            model = defaultModel(provider);
        }
        double temperature = parseTemperature(env.apply("ECHO_LLM_TEMPERATURE"));
        return new LlmConfig(provider, baseUrl, apiKey, model, temperature);
    }

    /**
     * 是否回落到 {@link MockLlmClient}：provider 缺省/为 mock，或非 local 供应商未配置 key，
     * 或缺 baseUrl —— 任一情况都不发起真实网络调用，保证无 key 环境可编译/跑测试/本地联调。
     */
    public boolean isMock() {
        if (provider == null || provider.equals("mock")) {
            return true;
        }
        if (baseUrl == null) {
            return true;
        }
        // 本地自建（vLLM/Ollama）通常无需 key，只要有 baseUrl 即视为可用
        if (provider.equals("local")) {
            return false;
        }
        return apiKey == null;
    }

    /** Chat Completions 完整端点。 */
    public String chatCompletionsUrl() {
        return baseUrl + "/chat/completions";
    }

    private static String defaultBaseUrl(String provider) {
        return switch (provider) {
            case "doubao" -> "https://ark.cn-beijing.volces.com/api/v3";
            case "deepseek" -> "https://api.deepseek.com/v1";
            case "qwen" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "openai" -> "https://api.openai.com/v1";
            case "local" -> "http://127.0.0.1:11434/v1"; // Ollama 默认 OpenAI 兼容端口
            default -> null;
        };
    }

    private static String defaultModel(String provider) {
        return switch (provider) {
            case "doubao" -> "doubao-pro-32k"; // 实际多为推理接入点 ID，建议显式配 ECHO_LLM_MODEL
            case "deepseek" -> "deepseek-chat";
            case "qwen" -> "qwen-plus";
            case "openai" -> "gpt-4o-mini";
            case "local" -> "qwen2.5:7b-instruct";
            default -> "";
        };
    }

    private static double parseTemperature(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TEMPERATURE;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            if (v < 0) {
                return 0;
            }
            return Math.min(v, 2.0);
        } catch (NumberFormatException e) {
            return DEFAULT_TEMPERATURE;
        }
    }

    /**
     * 空白归一为 null，并去除首尾空白。
     *
     * <p>必须 trim：env 文件若为 CRLF 换行，shell {@code source} 后每个值会带尾随
     * {@code \r}，会污染 URL 与 Authorization 头，导致鉴权失败且难以排查。</p>
     */
    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
