package com.echo.infra.embedding;

import java.util.function.Function;

/**
 * 向量嵌入供应商配置（对齐 {@link com.echo.infra.llm.LlmConfig} 的设计）。
 *
 * <p>从环境变量注入，<b>API key 不入库、不硬编码、不打印明文</b>：</p>
 * <ul>
 *   <li>{@code ECHO_EMBED_PROVIDER} —— qwen|openai|mock（默认 mock，保证无 key 也能编译/联调）</li>
 *   <li>{@code ECHO_EMBED_BASE_URL} —— OpenAI 兼容端点根（缺省时按 provider 取内置默认）</li>
 *   <li>{@code ECHO_EMBED_API_KEY} —— 密钥（缺省即回落 {@link MockEmbeddingClient}）</li>
 *   <li>{@code ECHO_EMBED_MODEL} —— 嵌入模型名（缺省时按 provider 取内置默认）</li>
 *   <li>{@code ECHO_EMBED_DIM} —— 目标维度（缺省 {@link IEmbeddingClient#DEFAULT_DIM}=768，与向量库对齐）</li>
 * </ul>
 *
 * <p>百炼 text-embedding-v3 提供 OpenAI 兼容的 {@code {baseUrl}/embeddings} 端点，且支持
 * {@code dimensions} 参数指定输出维度，故只需一套 {@link ApiEmbeddingClient} 即可。</p>
 */
public record EmbeddingConfig(String provider, String baseUrl, String apiKey, String model, int dimensions) {

    /** 从进程环境变量装配。 */
    public static EmbeddingConfig fromEnv() {
        return from(System::getenv);
    }

    /**
     * 从任意「键 → 值」查询函数装配（便于单测注入，不触真实环境变量）。
     *
     * @param env 形如 {@code key -> value} 的查询函数，未命中返回 {@code null}
     */
    public static EmbeddingConfig from(Function<String, String> env) {
        String provider = blankToNull(env.apply("ECHO_EMBED_PROVIDER"));
        provider = provider == null ? "mock" : provider.trim().toLowerCase();
        String apiKey = blankToNull(env.apply("ECHO_EMBED_API_KEY"));
        String baseUrl = blankToNull(env.apply("ECHO_EMBED_BASE_URL"));
        if (baseUrl == null) {
            baseUrl = defaultBaseUrl(provider);
        } else {
            baseUrl = stripTrailingSlash(baseUrl.trim());
        }
        String model = blankToNull(env.apply("ECHO_EMBED_MODEL"));
        if (model == null) {
            model = defaultModel(provider);
        }
        int dimensions = parseDim(env.apply("ECHO_EMBED_DIM"));
        return new EmbeddingConfig(provider, baseUrl, apiKey, model, dimensions);
    }

    /**
     * 是否回落到 {@link MockEmbeddingClient}：provider 缺省/为 mock、缺 baseUrl、或未配置 key ——
     * 任一情况都不发起真实网络调用，保证无 key 环境可编译/跑测试/本地联调（默认仍走 mock，不破坏现有行为）。
     */
    public boolean isMock() {
        if (provider == null || provider.equals("mock")) {
            return true;
        }
        if (baseUrl == null) {
            return true;
        }
        return apiKey == null;
    }

    /** Embeddings 完整端点。 */
    public String embeddingsUrl() {
        return baseUrl + "/embeddings";
    }

    private static String defaultBaseUrl(String provider) {
        return switch (provider) {
            // 百炼 compatible-mode（OpenAI 兼容），与 LlmConfig 的 qwen 端点一致
            case "qwen" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "openai" -> "https://api.openai.com/v1";
            default -> null;
        };
    }

    private static String defaultModel(String provider) {
        return switch (provider) {
            case "qwen" -> "text-embedding-v3";
            case "openai" -> "text-embedding-3-small";
            default -> "";
        };
    }

    private static int parseDim(String raw) {
        if (raw == null || raw.isBlank()) {
            return IEmbeddingClient.DEFAULT_DIM;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : IEmbeddingClient.DEFAULT_DIM;
        } catch (NumberFormatException e) {
            return IEmbeddingClient.DEFAULT_DIM;
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
