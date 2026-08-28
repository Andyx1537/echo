package com.echo.infra.vision;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@link IVisionClient} 的真实实现：走百炼 <b>qwen-vl</b> 的 <b>OpenAI 兼容 Chat Completions</b>
 * 多模态端点（{@code messages} 里带 {@code image_url}），用 JDK 自带 {@link HttpClient} + 现有
 * Gson，<b>不引入任何第三方依赖</b>。
 *
 * <p>优雅降级（对齐 {@link com.echo.infra.llm.ApiLlmClient}）：连接/超时/非 2xx/解析失败等
 * <b>任何异常都不抛给上层</b>，而是委托 {@code fallback}（通常为 {@link StubVisionClient}）返回中性默认，
 * 保证坏网络/无 key 下建档流程不塌，前端仍以「就是 ta / 不是 ta？」让用户确认或纠正。</p>
 *
 * <p>配置见 {@link VisionConfig}（env 注入，key 不入库/不打印）。切换供应商只改环境变量、不动业务代码。</p>
 */
@Slf4j
public class ApiVisionClient implements IVisionClient {

    private static final Gson GSON = new Gson();

    /**
     * 前端词表（{@code SPECIES_LIST}）：模型输出对齐到这些词，无法归类降级为「其他」，绝不乱认。
     */
    private static final Set<String> SPECIES_VOCAB = Set.of(
            "狗", "猫", "兔", "仓鼠", "龙猫", "豚鼠", "刺猬", "松鼠", "鸟", "鹦鹉", "其他");

    /** 归类失败时的兜底物种。 */
    private static final String UNKNOWN_SPECIES = "其他";

    private final VisionConfig config;
    private final IVisionClient fallback;
    private final HttpClient http;
    private final Duration requestTimeout;

    /**
     * 默认超时 {@code 30s}：正常路径靠 {@link ImageCompressor} 把图压到几百 KB，端到端只要几秒；
     * 放宽到 30s 只是给偶发抖动留余量，<b>不是</b>用来兜住「发原图」那种一分钟起步的调用。
     */
    public ApiVisionClient(VisionConfig config, IVisionClient fallback) {
        this(config, fallback, defaultHttpClient(), Duration.ofSeconds(30));
    }

    /** 供单测注入自定义 {@link HttpClient} / 超时（真实网络调用不在单测中发起）。 */
    public ApiVisionClient(VisionConfig config, IVisionClient fallback, HttpClient http, Duration requestTimeout) {
        this.config = config;
        this.fallback = fallback != null ? fallback : new StubVisionClient();
        this.http = http;
        this.requestTimeout = requestTimeout;
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 按肖像资源（图片 URL 或 base64/data-uri）识别宠物物种。
     *
     * @param resourceId 图片 URL 或 {@code data:image/...;base64,...}（直接作为 qwen-vl 的 image_url）
     * @return 识别到的主体列表（按 confidence 降序）；任何异常/空结果委托 {@code fallback}
     */
    @Override
    public List<DetectSubject> detect(String resourceId) {
        return detectWithSource(resourceId).subjects();
    }

    /**
     * 同 {@link #detect(String)}，并如实标注来源：真模型识别到 → {@code model}；
     * 任何异常/非 2xx/空结果 → 委托 {@code fallback} 并标 {@code fallback}（前端据此不当识别结果展示）。
     */
    @Override
    public DetectResult detectWithSource(String resourceId) {
        List<DetectSubject> subjects = call(resourceId);
        if (subjects == null || subjects.isEmpty()) {
            log.warn("ApiVisionClient 无有效识别结果，走 fallback, provider={}", config.provider());
            return DetectResult.fallback(fallback.detect(resourceId));
        }
        return DetectResult.model(subjects);
    }

    /**
     * 发起一次 qwen-vl 多模态调用。任何异常/非 2xx/空结果都返回 {@code null}（由调用方委托 fallback），
     * 全程 try-catch，绝不向上抛。
     */
    private List<DetectSubject> call(String resourceId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.chatCompletionsUrl()))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (config.apiKey() == null ? "" : config.apiKey()))
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(resourceId), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                log.warn("ApiVisionClient 失败: step=http-status, status={}, ref={}, body={}",
                        resp.statusCode(), describeRef(resourceId), truncate(resp.body()));
                return null;
            }
            String content = extractContent(resp.body());
            if (content == null || content.isBlank()) {
                log.warn("ApiVisionClient 失败: step=extract-content, 响应缺少 choices[0].message.content, body={}",
                        truncate(resp.body()));
                return null;
            }
            return parseSubjects(content);
        } catch (Exception e) {
            log.warn("ApiVisionClient 失败: step=network, provider={}, model={}, ref={}, msg={}",
                    config.provider(), config.model(), describeRef(resourceId), e.toString());
            return null;
        }
    }

    /**
     * 图片引用的日志摘要：data-uri 只记协议头与长度（一整串 base64 打进日志会把日志撑爆），
     * 普通 URL 原样记（便于排查外链不可达）。
     */
    private static String describeRef(String ref) {
        if (ref == null) {
            return "null";
        }
        if (ref.startsWith("data:")) {
            int comma = ref.indexOf(',');
            String head = comma > 0 ? ref.substring(0, comma) : "data:";
            return head + ",<" + ref.length() + " chars>";
        }
        return truncate(ref);
    }

    /**
     * 组装 qwen-vl（OpenAI 兼容）多模态请求体：system 约束输出词表与 JSON 结构，user 带文本 + 图片。
     */
    private String buildRequestBody(String resourceId) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());

        JsonArray messages = new JsonArray();

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content",
                "你是宠物物种识别助手。只识别图片中动物主体的物种，"
                        + "物种词必须从这个词表中选：狗、猫、兔、仓鼠、龙猫、豚鼠、刺猬、松鼠、鸟、鹦鹉、其他；"
                        + "无法确定就用「其他」，绝不臆造。"
                        + "只输出 JSON，形如 {\"subjects\":[{\"species\":\"狗\",\"confidence\":0.9}]}，"
                        + "confidence 取 0~1；识别到多个主体就返回多项，按 confidence 降序。");
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "识别这张图片里的宠物物种。");
        parts.add(textPart);
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", resourceId);
        imagePart.add("image_url", imageUrl);
        parts.add(imagePart);
        user.add("content", parts);
        messages.add(user);

        body.add("messages", messages);
        return GSON.toJson(body);
    }

    /**
     * 解析 {@code choices[0].message.content}；qwen-vl 的 content 可能是字符串或分段数组，两种都兼容。
     * 结构缺失时返回 {@code null}。
     */
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
        JsonElement contentEl = message.get("content");
        if (contentEl.isJsonPrimitive()) {
            return contentEl.getAsString().trim();
        }
        // content 为分段数组时，拼接其中的 text 段
        if (contentEl.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement part : contentEl.getAsJsonArray()) {
                if (part.isJsonObject()) {
                    JsonObject p = part.getAsJsonObject();
                    if (p.has("text") && p.get("text").isJsonPrimitive()) {
                        sb.append(p.get("text").getAsString());
                    }
                }
            }
            return sb.toString().trim();
        }
        return null;
    }

    /**
     * 把模型输出的 JSON 文本解析为 {@link DetectSubject} 列表（按 confidence 降序）。
     * 兼容 markdown 代码围栏；物种对齐词表，未知降级「其他」；无有效项返回 {@code null}。
     */
    private List<DetectSubject> parseSubjects(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String json = stripCodeFence(content);
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            log.warn("ApiVisionClient 失败: step=parse-json, 模型输出非法 JSON: {}", truncate(content));
            return null;
        }
        if (!root.isJsonObject()) {
            return null;
        }
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("subjects") || !obj.get("subjects").isJsonArray()) {
            log.warn("ApiVisionClient 失败: step=parse-empty, 模型输出缺少 subjects 数组: {}", truncate(content));
            return null;
        }
        List<DetectSubject> subjects = new ArrayList<>();
        for (JsonElement el : obj.getAsJsonArray("subjects")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject s = el.getAsJsonObject();
            String species = normalizeSpecies(
                    s.has("species") && s.get("species").isJsonPrimitive() ? s.get("species").getAsString() : null);
            double confidence = clamp01(
                    s.has("confidence") && s.get("confidence").isJsonPrimitive() ? s.get("confidence").getAsDouble() : 0.5);
            subjects.add(DetectSubject.of(DetectSubject.SubjectType.ANIMAL, species, confidence));
        }
        if (subjects.isEmpty()) {
            return null;
        }
        subjects.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return subjects;
    }

    /** 物种归一化：对齐前端词表，未命中降级为「其他」。 */
    private static String normalizeSpecies(String raw) {
        if (raw == null) {
            return UNKNOWN_SPECIES;
        }
        String s = raw.trim();
        return SPECIES_VOCAB.contains(s) ? s : UNKNOWN_SPECIES;
    }

    private static double clamp01(double v) {
        if (v < 0) {
            return 0;
        }
        return Math.min(v, 1.0);
    }

    /** 去掉 markdown 代码围栏（```json ... ``` 或 ``` ... ```）。 */
    private static String stripCodeFence(String s) {
        String t = s.trim();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstNl = t.indexOf('\n');
        if (firstNl >= 0) {
            t = t.substring(firstNl + 1);
        }
        if (t.endsWith("```")) {
            t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
