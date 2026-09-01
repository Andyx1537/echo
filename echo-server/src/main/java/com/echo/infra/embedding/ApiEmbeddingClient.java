package com.echo.infra.embedding;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@link IEmbeddingClient} 的真实实现：走百炼 <b>text-embedding-v3</b> 的 <b>OpenAI 兼容 Embeddings</b>
 * 端点（{@code {baseUrl}/embeddings}），用 JDK 自带 {@link HttpClient} + 现有 Gson，
 * <b>不引入任何第三方依赖</b>。
 *
 * <p>优雅降级（对齐 {@link com.echo.infra.llm.ApiLlmClient}）：连接/超时/非 2xx/解析失败/维度不符等
 * <b>任何异常都不抛给上层</b>，而是委托 {@code fallback}（通常为 {@link MockEmbeddingClient}）返回确定性
 * 伪向量，保证坏网络/无 key 下向量编码不塌、主流程可继续。</p>
 *
 * <p>配置见 {@link EmbeddingConfig}（env 注入，key 不入库/不打印）。切换供应商只改环境变量、不动业务代码。</p>
 */
@Slf4j
public class ApiEmbeddingClient implements IEmbeddingClient {

    private static final Gson GSON = new Gson();

    private final EmbeddingConfig config;
    private final IEmbeddingClient fallback;
    private final HttpClient http;
    private final Duration requestTimeout;

    public ApiEmbeddingClient(EmbeddingConfig config, IEmbeddingClient fallback) {
        this(config, fallback, defaultHttpClient(), Duration.ofSeconds(20));
    }

    /** 供单测注入自定义 {@link HttpClient} / 超时（真实网络调用不在单测中发起）。 */
    public ApiEmbeddingClient(EmbeddingConfig config, IEmbeddingClient fallback, HttpClient http, Duration requestTimeout) {
        this.config = config;
        this.fallback = fallback != null ? fallback : new MockEmbeddingClient(config.dimensions());
        this.http = http;
        this.requestTimeout = requestTimeout;
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public int dimension() {
        return config.dimensions();
    }

    @Override
    public EmbeddingDescriptor descriptor() {
        return new EmbeddingDescriptor(config.provider(), config.model(), config.version(), config.dimensions());
    }

    @Override
    public float[] embed(String text) {
        float[] v = call(text);
        if (v == null || v.length == 0) {
            log.warn("ApiEmbeddingClient 无有效向量，走 fallback, provider={}", config.provider());
            v = fallback.embed(text);
        }
        if (v.length != dimension()) {
            throw new IllegalStateException("embedding dimension mismatch: expected=" + dimension()
                    + ", actual=" + v.length);
        }
        return v;
    }

    /**
     * 发起一次 Embeddings 调用。任何异常/非 2xx/空结果都返回 {@code null}（由调用方委托 fallback），
     * 全程 try-catch，绝不向上抛。
     */
    private float[] call(String text) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.embeddingsUrl()))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (config.apiKey() == null ? "" : config.apiKey()))
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(text), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                log.warn("ApiEmbeddingClient 非 2xx: status={}, body={}", resp.statusCode(), truncate(resp.body()));
                return null;
            }
            return extractEmbedding(resp.body());
        } catch (Exception e) {
            log.warn("ApiEmbeddingClient 调用失败，将走兜底: provider={}, model={}, msg={}",
                    config.provider(), config.model(), e.toString());
            return null;
        }
    }

    /** 组装 OpenAI 兼容请求体：{@code {model, input, dimensions, encoding_format}}。 */
    private String buildRequestBody(String text) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.addProperty("input", text == null ? "" : text);
        body.addProperty("dimensions", config.dimensions());
        body.addProperty("encoding_format", "float");
        return GSON.toJson(body);
    }

    /** 解析 {@code data[0].embedding}；结构缺失/为空时返回 {@code null}。 */
    private float[] extractEmbedding(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) {
            return null;
        }
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("data") || !obj.get("data").isJsonArray()) {
            return null;
        }
        JsonArray data = obj.getAsJsonArray("data");
        if (data.isEmpty() || !data.get(0).isJsonObject()) {
            return null;
        }
        JsonObject first = data.get(0).getAsJsonObject();
        if (!first.has("embedding") || !first.get("embedding").isJsonArray()) {
            return null;
        }
        JsonArray arr = first.getAsJsonArray("embedding");
        if (arr.isEmpty()) {
            return null;
        }
        float[] v = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            v[i] = arr.get(i).getAsFloat();
        }
        return v;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
