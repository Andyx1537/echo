package com.echo.infra.embedding;

/** 可持久化的嵌入模型身份；不同身份的向量禁止混合检索。 */
public record EmbeddingDescriptor(String provider, String model, String version, int dimensions) {
    public EmbeddingDescriptor {
        provider = normalize(provider, "unknown");
        model = normalize(model, "unknown");
        version = normalize(version, "default");
        if (dimensions <= 0) throw new IllegalArgumentException("embedding dimensions must be positive");
    }
    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
