package com.echo.infra.safety;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 走 HTTP 的内容安全客户端。
 *
 * <h2>为什么是"通用 HTTP + 可插拔签名"而不是直接接某一家</h2>
 *
 * <p>阿里云内容安全与腾讯云 CMS 的<b>鉴权方式互不兼容</b>（前者 ROA/RPC 签名 v3，
 * 后者 TC3-HMAC-SHA256），请求/应答字段名也不同。把签名抽成 {@link RequestSigner}
 * 之后，选型落定时只需新增一个 signer + 一份字段映射，
 * <b>失败方向、配额、熔断、就绪判据这些容易写错的部分不用重写</b>。</p>
 *
 * <p>🔴 本类<b>不做任何失败兜底</b>：所有异常原样抛 {@link ContentSafetyException}，
 * 由 {@link ContentSafetyGate} 统一收成「未通过」。</p>
 *
 * <h2>字段映射</h2>
 *
 * <p>请求体 {@code {"text": "..."}}，应答体期望 {@code {"pass": bool}} 或
 * {@code {"label": "..."}}（{@code label} 非 {@code normal} 即视为命中）。
 * ⚠️ 这是<b>占位契约</b>，选型落定后按厂商实际字段改 {@link #parse}。
 * 解析不出上述任一字段时抛 {@link ContentSafetyException.Reason#MALFORMED_RESPONSE} ——
 * 🔴 不要在这里"猜一个默认值"，猜出来的默认值只能是放行。</p>
 */
@Slf4j
public final class HttpContentSafetyClient implements IContentSafetyClient {

    /**
     * 厂商签名策略。
     *
     * <p>实现负责往请求上加鉴权头。{@link #NONE} 用于自建网关或简单 Bearer token 场景。</p>
     */
    public interface RequestSigner {
        void sign(HttpRequest.Builder request, ContentSafetyConfig config, String body);

        /** 仅加 {@code Authorization: Bearer <key>}，适用于自建代理 / 简单 token 鉴权。 */
        RequestSigner NONE = (request, config, body) ->
                request.header("Authorization", "Bearer " + config.apiKey());
    }

    private final ContentSafetyConfig config;
    private final HttpClient http;
    private final RequestSigner signer;

    public HttpContentSafetyClient(ContentSafetyConfig config) {
        this(config, defaultHttpClient(config), RequestSigner.NONE);
    }

    public HttpContentSafetyClient(ContentSafetyConfig config, HttpClient http, RequestSigner signer) {
        this.config = config;
        this.http = http;
        this.signer = signer;
    }

    private static HttpClient defaultHttpClient(ContentSafetyConfig config) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(200, config.timeoutMs())))
                .build();
    }

    @Override
    public String provider() {
        return config.provider();
    }

    @Override
    public ContentSafetyVerdict inspectText(String text) throws ContentSafetyException {
        JsonObject payload = new JsonObject();
        payload.addProperty("text", text);
        String body = payload.toString();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.endpoint()))
                .timeout(Duration.ofMillis(config.timeoutMs()))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        signer.sign(builder, config, body);

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new ContentSafetyException(ContentSafetyException.Reason.TIMEOUT,
                    "内容安全调用超时 " + config.timeoutMs() + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContentSafetyException(ContentSafetyException.Reason.TRANSPORT, "调用被中断", e);
        } catch (Exception e) {
            throw new ContentSafetyException(ContentSafetyException.Reason.TRANSPORT,
                    "内容安全调用传输失败: " + e.getClass().getSimpleName(), e);
        }

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new ContentSafetyException(ContentSafetyException.Reason.AUTH_FAILED,
                    "内容安全鉴权失败 HTTP " + status);
        }
        if (status == 429) {
            throw new ContentSafetyException(ContentSafetyException.Reason.RATE_LIMITED,
                    "内容安全服务端限流 HTTP 429");
        }
        if (status < 200 || status >= 300) {
            throw new ContentSafetyException(ContentSafetyException.Reason.TRANSPORT,
                    "内容安全返回 HTTP " + status);
        }
        return parse(response.body());
    }

    /**
     * 解析应答。
     *
     * <p>🔴 任何解析不确定的情况都抛 {@link ContentSafetyException.Reason#MALFORMED_RESPONSE}，
     * 由闸门收成"未通过"。这里是最容易埋雷的地方：一旦写成"解析失败当通过"，
     * 供应商改一次字段名就等于整关关掉，而且返回码全是 200，监控上完全正常。</p>
     */
    private ContentSafetyVerdict parse(String raw) throws ContentSafetyException {
        if (raw == null || raw.isBlank()) {
            throw new ContentSafetyException(ContentSafetyException.Reason.MALFORMED_RESPONSE,
                    "内容安全应答为空");
        }
        JsonObject json;
        try {
            JsonElement el = JsonParser.parseString(raw);
            if (!el.isJsonObject()) {
                throw new ContentSafetyException(ContentSafetyException.Reason.MALFORMED_RESPONSE,
                        "内容安全应答不是 JSON 对象");
            }
            json = el.getAsJsonObject();
        } catch (ContentSafetyException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ContentSafetyException(ContentSafetyException.Reason.MALFORMED_RESPONSE,
                    "内容安全应答无法解析为 JSON", e);
        }

        if (json.has("pass") && json.get("pass").isJsonPrimitive()) {
            boolean pass = json.get("pass").getAsBoolean();
            return pass ? ContentSafetyVerdict.pass()
                    : ContentSafetyVerdict.blocked(optString(json, "label", "unknown"));
        }
        if (json.has("label") && json.get("label").isJsonPrimitive()) {
            String label = json.get("label").getAsString();
            return "normal".equalsIgnoreCase(label)
                    ? ContentSafetyVerdict.pass()
                    : ContentSafetyVerdict.blocked(label);
        }
        throw new ContentSafetyException(ContentSafetyException.Reason.MALFORMED_RESPONSE,
                "内容安全应答缺少 pass/label 字段");
    }

    private static String optString(JsonObject json, String key, String fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
    }
}
