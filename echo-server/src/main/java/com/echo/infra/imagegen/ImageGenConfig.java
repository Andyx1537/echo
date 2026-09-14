package com.echo.infra.imagegen;

import java.net.URI;
import java.util.function.Function;

/**
 * 定妆出图配置。API key 走环境变量，不入库。
 *
 * <p>未设 {@code ECHO_IMGGEN_PROVIDER=wanx} 或没有钥匙时回落 mock，建档仍出渐变色块。</p>
 */
public record ImageGenConfig(String provider, String workspaceBase, String apiKey, String model,
                             String function, String prompt, double strength, int count) {

    public static final String DEFAULT_MODEL = "wanx2.1-imageedit";
    public static final String DEFAULT_FUNCTION = "stylization_all";
    public static final String DEFAULT_PROMPT = "转换成法国绘本风格";
    public static final double DEFAULT_STRENGTH = 0.45;
    public static final int DEFAULT_COUNT = 3;

    public static ImageGenConfig fromEnv() {
        return from(System::getenv);
    }

    public static ImageGenConfig from(Function<String, String> env) {
        String provider = blankToNull(env.apply("ECHO_IMGGEN_PROVIDER"));
        provider = provider == null ? "mock" : provider.trim().toLowerCase();
        String apiKey = firstNonBlank(env.apply("ECHO_IMGGEN_API_KEY"), env.apply("ECHO_LLM_API_KEY"));
        String model = firstNonBlank(env.apply("ECHO_IMGGEN_MODEL"), DEFAULT_MODEL);
        String function = firstNonBlank(env.apply("ECHO_IMGGEN_FUNCTION"), DEFAULT_FUNCTION);
        String prompt = firstNonBlank(env.apply("ECHO_IMGGEN_PROMPT"), DEFAULT_PROMPT);
        return new ImageGenConfig(provider, workspaceBase(env), apiKey, model, function, prompt,
                parseStrength(env.apply("ECHO_IMGGEN_STRENGTH")), parseCount(env.apply("ECHO_IMGGEN_COUNT")));
    }

    public boolean isMock() {
        if (provider == null || provider.equals("mock") || workspaceBase == null) {
            return true;
        }
        return apiKey == null;
    }

    public String imageSynthesisUrl() {
        return workspaceBase + "/api/v1/services/aigc/image2image/image-synthesis";
    }

    public String taskUrl(String taskId) {
        return workspaceBase + "/api/v1/tasks/" + taskId;
    }

    private static String workspaceBase(Function<String, String> env) {
        String explicit = blankToNull(env.apply("ECHO_IMGGEN_BASE_URL"));
        if (explicit != null) {
            return hostOnly(explicit);
        }
        String llm = blankToNull(env.apply("ECHO_LLM_BASE_URL"));
        if (llm != null && llm.contains("maas.aliyuncs.com")) {
            return hostOnly(llm);
        }
        return "https://dashscope.aliyuncs.com";
    }

    private static String hostOnly(String raw) {
        URI uri = URI.create(stripTrailingSlash(raw.trim()));
        if (uri.getScheme() == null || uri.getHost() == null) {
            return stripTrailingSlash(raw.trim());
        }
        int port = uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + (port > 0 ? ":" + port : "");
    }

    private static String firstNonBlank(String a, String b) {
        String first = blankToNull(a);
        return first != null ? first : blankToNull(b);
    }

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

    private static double parseStrength(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_STRENGTH;
        }
        try {
            return Math.min(1.0, Math.max(0.0, Double.parseDouble(raw.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_STRENGTH;
        }
    }

    private static int parseCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_COUNT;
        }
        try {
            return Math.min(4, Math.max(1, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_COUNT;
        }
    }
}
