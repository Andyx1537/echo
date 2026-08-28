package com.echo.infra.vision;

import java.util.function.Function;

/**
 * 视觉识别供应商配置（对齐 {@link com.echo.infra.llm.LlmConfig} 的设计）。
 *
 * <p>从环境变量注入，<b>API key 不入库、不硬编码、不打印明文</b>：</p>
 * <ul>
 *   <li>{@code ECHO_VISION_PROVIDER} —— qwen|stub（默认 stub，保证无 key 也能编译/联调）</li>
 *   <li>{@code ECHO_VISION_BASE_URL} —— OpenAI 兼容端点根（缺省时按 provider 取内置默认）</li>
 *   <li>{@code ECHO_VISION_API_KEY} —— 密钥（缺省即回落 {@link StubVisionClient}）</li>
 *   <li>{@code ECHO_VISION_MODEL} —— 多模态模型名（缺省时按 provider 取内置默认）</li>
 *   <li>{@code ECHO_VISION_MAX_EDGE} —— 送模型前图片缩放的最长边 px（缺省
 *       {@link ImageCompressor#DEFAULT_MAX_EDGE}；调大更清晰但更慢，耗时与视觉 token 数正相关）</li>
 * </ul>
 *
 * <p>百炼 qwen-vl 提供 OpenAI 兼容的 Chat Completions 端点（messages 里带 {@code image_url}），
 * 故只需一套 {@link ApiVisionClient} 走 {@code {baseUrl}/chat/completions} 即可。</p>
 */
public record VisionConfig(String provider, String baseUrl, String apiKey, String model, int maxImageEdge) {

    /** 从进程环境变量装配。 */
    public static VisionConfig fromEnv() {
        return from(System::getenv);
    }

    /**
     * 从任意「键 → 值」查询函数装配（便于单测注入，不触真实环境变量）。
     *
     * @param env 形如 {@code key -> value} 的查询函数，未命中返回 {@code null}
     */
    public static VisionConfig from(Function<String, String> env) {
        String provider = blankToNull(env.apply("ECHO_VISION_PROVIDER"));
        provider = provider == null ? "stub" : provider.trim().toLowerCase();
        String apiKey = blankToNull(env.apply("ECHO_VISION_API_KEY"));
        String baseUrl = blankToNull(env.apply("ECHO_VISION_BASE_URL"));
        if (baseUrl == null) {
            baseUrl = defaultBaseUrl(provider);
        } else {
            baseUrl = stripTrailingSlash(baseUrl.trim());
        }
        String model = blankToNull(env.apply("ECHO_VISION_MODEL"));
        if (model == null) {
            model = defaultModel(provider);
        }
        return new VisionConfig(provider, baseUrl, apiKey, model, maxEdge(env.apply("ECHO_VISION_MAX_EDGE")));
    }

    /** 解析最长边配置；缺省/非法值回落内置默认（配错一个数字不该让建档挂掉）。 */
    private static int maxEdge(String raw) {
        String v = blankToNull(raw);
        if (v == null) {
            return ImageCompressor.DEFAULT_MAX_EDGE;
        }
        try {
            int n = Integer.parseInt(v);
            return n > 0 ? n : ImageCompressor.DEFAULT_MAX_EDGE;
        } catch (NumberFormatException e) {
            return ImageCompressor.DEFAULT_MAX_EDGE;
        }
    }

    /**
     * 是否回落到 {@link StubVisionClient}：provider 缺省/为 stub、缺 baseUrl、或未配置 key ——
     * 任一情况都不发起真实网络调用，保证无 key 环境可编译/跑测试/本地联调。
     */
    public boolean isStub() {
        if (provider == null || provider.equals("stub")) {
            return true;
        }
        if (baseUrl == null) {
            return true;
        }
        return apiKey == null;
    }

    /** Chat Completions 完整端点（qwen-vl 走 OpenAI 兼容多模态）。 */
    public String chatCompletionsUrl() {
        return baseUrl + "/chat/completions";
    }

    private static String defaultBaseUrl(String provider) {
        return switch (provider) {
            // 百炼 compatible-mode（OpenAI 兼容），与 LlmConfig 的 qwen 端点一致
            case "qwen" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            default -> null;
        };
    }

    private static String defaultModel(String provider) {
        return switch (provider) {
            case "qwen" -> "qwen-vl-plus";
            default -> "";
        };
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
