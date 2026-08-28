package com.echo.infra.llm;

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
 * {@link ILlmClient} 的真实实现：走 <b>OpenAI 兼容的 Chat Completions</b> 端点
 * （豆包 / DeepSeek / Qwen / OpenAI / 本地 vLLM·Ollama 均适用），用 JDK 自带
 * {@link HttpClient} + 现有 Gson，<b>不引入任何第三方依赖</b>。
 *
 * <p>优雅降级（任务约束 §1.5）：连接/超时/非 2xx/解析失败等任何异常，都不抛给上层、更不 500 崩接口，
 * 而是委托 {@code fallback}（通常为 {@link MockLlmClient}）返回兜底文案，配合 {@code CopyGuardFilter}
 * 与调用侧的温柔文案池，保证坏网络下体验不塌。</p>
 *
 * <p>配置见 {@link LlmConfig}（env 注入，key 不入库）。切换供应商只改环境变量、不动业务代码。</p>
 */
@Slf4j
public class ApiLlmClient implements ILlmClient {

    private static final Gson GSON = new Gson();

    private final LlmConfig config;
    private final ILlmClient fallback;
    private final HttpClient http;
    private final Duration requestTimeout;

    public ApiLlmClient(LlmConfig config, ILlmClient fallback) {
        this(config, fallback, defaultHttpClient(), Duration.ofSeconds(20));
    }

    /** 供单测注入自定义 {@link HttpClient} / 超时（真实网络调用不在单测中发起）。 */
    public ApiLlmClient(LlmConfig config, ILlmClient fallback, HttpClient http, Duration requestTimeout) {
        this.config = config;
        this.fallback = fallback != null ? fallback : new MockLlmClient();
        this.http = http;
        this.requestTimeout = requestTimeout;
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String enrich(String rawPrefs) {
        // 意识档案补全：拿不到有效输出时按接口约定兜底返回原始偏好，绝不清空用户输入
        String prompt = "请把下面这段用户自拟的偏好，扩写/补足为结构化偏好文本，保持原意、简洁：\n" + rawPrefs;
        String out = call(prompt);
        return (out == null || out.isBlank()) ? rawPrefs : out;
    }

    @Override
    public String complete(String prompt) {
        String out = call(prompt);
        if (out == null || out.isBlank()) {
            log.warn("ApiLlmClient 无有效输出，走 fallback, provider={}", config.provider());
            return fallback.complete(prompt);
        }
        return out;
    }

    /**
     * 发起一次 Chat Completions 调用。任何异常/非 2xx/空结果都返回 {@code null}（由调用方决定兜底），
     * 全程 try-catch，绝不向上抛。
     */
    private String call(String prompt) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.chatCompletionsUrl()))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (config.apiKey() == null ? "" : config.apiKey()))
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(prompt), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                log.warn("ApiLlmClient 非 2xx: status={}, body={}", resp.statusCode(), truncate(resp.body()));
                return null;
            }
            return extractContent(resp.body());
        } catch (Exception e) {
            log.warn("ApiLlmClient 调用失败，将走兜底: provider={}, model={}, msg={}",
                    config.provider(), config.model(), e.toString());
            return null;
        }
    }

    /** 组装 OpenAI 兼容请求体：单条 user 消息（system 基调约束已由调用方拼入 prompt）。 */
    private String buildRequestBody(String prompt) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.addProperty("temperature", config.temperature());
        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", prompt);
        messages.add(user);
        body.add("messages", messages);
        return GSON.toJson(body);
    }

    /** 解析 {@code choices[0].message.content}；结构缺失时返回 {@code null}。 */
    private String extractContent(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) {
            return null;
        }
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("choices") || !obj.get("choices").isJsonArray()) {
            return null;
        }
        JsonArray choices = obj.getAsJsonArray("choices");
        if (choices.isEmpty() || !choices.get(0).isJsonObject()) {
            return null;
        }
        JsonObject first = choices.get(0).getAsJsonObject();
        if (!first.has("message") || !first.get("message").isJsonObject()) {
            return null;
        }
        JsonObject message = first.getAsJsonObject("message");
        if (!message.has("content") || message.get("content").isJsonNull()) {
            return null;
        }
        return message.get("content").getAsString().trim();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
