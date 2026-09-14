package com.echo.infra.imagegen;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 万相图生图。提交异步任务后轮询，再把结果图拉回成本地字节。
 */
@Slf4j
public final class ApiImageGenClient implements IImageGenClient {
    private static final Gson GSON = new Gson();
    private static final Duration POLL = Duration.ofSeconds(3);
    private static final int MAX_POLLS = 30;

    private final ImageGenConfig config;
    private final HttpClient http;
    private final Duration requestTimeout;

    public ApiImageGenClient(ImageGenConfig config) {
        this(config, defaultHttp(), Duration.ofSeconds(90));
    }

    public ApiImageGenClient(ImageGenConfig config, HttpClient http, Duration requestTimeout) {
        this.config = config;
        this.http = http;
        this.requestTimeout = requestTimeout;
    }

    private static HttpClient defaultHttp() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public List<GeneratedImage> stylize(String baseImageRef) {
        if (baseImageRef == null || baseImageRef.isBlank()) {
            throw new ImageGenException("没有可用的肖像底图");
        }
        try {
            String taskId = submit(baseImageRef);
            List<String> urls = poll(taskId);
            if (urls.isEmpty()) {
                throw new ImageGenException("出图完成但没有地址");
            }
            List<GeneratedImage> out = new ArrayList<>();
            for (String url : urls) {
                out.add(new GeneratedImage(url, null, "image/png"));
            }
            return out;
        } catch (ImageGenException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[imggen] stylize failed class={}", e.getClass().getName(), e);
            throw new ImageGenException("出图失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()), e);
        }
    }

    private String submit(String baseImageRef) throws Exception {
        JsonObject input = new JsonObject();
        input.addProperty("function", config.function());
        input.addProperty("prompt", config.prompt());
        input.addProperty("base_image_url", baseImageRef);
        JsonObject parameters = new JsonObject();
        parameters.addProperty("n", config.count());
        parameters.addProperty("strength", config.strength());
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.add("input", input);
        body.add("parameters", parameters);

        HttpRequest request = HttpRequest.newBuilder(URI.create(config.imageSynthesisUrl()))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        log.info("[imggen] submitting provider={} model={} bodyChars={}",
                config.provider(), config.model(), GSON.toJson(body).length());
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("[imggen] submit threw class={}", e.getClass().getName(), e);
            throw e;
        }
        JsonObject parsed = parse(response.body());
        log.info("[imggen] submit http={} bodyChars={}", response.statusCode(),
                response.body() == null ? 0 : response.body().length());
        if (response.statusCode() / 100 != 2) {
            throw new ImageGenException("出图提交被拒：" + messageOf(parsed));
        }
        if (!output(parsed).has("task_id")) {
            throw new ImageGenException("出图提交没有任务号");
        }
        String taskId = output(parsed).get("task_id").getAsString();
        if (taskId == null || taskId.isBlank()) {
            throw new ImageGenException("出图提交没有任务号");
        }
        log.info("[imggen] submitted task provider={} model={}", config.provider(), config.model());
        return taskId;
    }

    private List<String> poll(String taskId) throws Exception {
        for (int i = 0; i < MAX_POLLS; i++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.taskUrl(taskId)))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject parsed = parse(response.body());
            JsonObject output = output(parsed);
            String status = output.has("task_status") ? output.get("task_status").getAsString() : "";
            List<String> urls = urlsOf(output);
            log.info("[imggen] poll status={} urls={} http={} bodyChars={}",
                    status, urls.size(), response.statusCode(),
                    response.body() == null ? 0 : response.body().length());
            if ("SUCCEEDED".equals(status)) {
                if (!urls.isEmpty()) {
                    return urls;
                }
                log.warn("[imggen] task succeeded but urls still empty, keep polling");
            }
            if ("FAILED".equals(status) || "CANCELED".equals(status) || "UNKNOWN".equals(status)) {
                throw new ImageGenException("出图任务失败：" + messageOf(parsed, output));
            }
            Thread.sleep(POLL.toMillis());
        }
        throw new ImageGenException("出图等待超时");
    }

    private static JsonObject parse(String body) {
        if (body == null || body.isBlank()) {
            return new JsonObject();
        }
        return GSON.fromJson(body, JsonObject.class);
    }

    private static JsonObject output(JsonObject parsed) {
        if (parsed.has("output") && parsed.get("output").isJsonObject()) {
            return parsed.getAsJsonObject("output");
        }
        return new JsonObject();
    }

    private static String messageOf(JsonObject parsed) {
        return messageOf(parsed, output(parsed));
    }

    private static String messageOf(JsonObject parsed, JsonObject output) {
        if (parsed.has("message")) {
            return parsed.get("message").getAsString();
        }
        if (output.has("message")) {
            return output.get("message").getAsString();
        }
        if (parsed.has("code")) {
            return parsed.get("code").getAsString();
        }
        if (output.has("code")) {
            return output.get("code").getAsString();
        }
        return "unknown";
    }

    private static List<String> urlsOf(JsonObject output) {
        List<String> urls = new ArrayList<>();
        collectUrls(output.has("results") ? output.get("results") : null, urls);
        if (urls.isEmpty()) {
            collectUrls(output.has("output") ? output.get("output") : null, urls);
        }
        return urls;
    }

    private static void collectUrls(com.google.gson.JsonElement node, List<String> urls) {
        if (node == null || node.isJsonNull()) {
            return;
        }
        if (node.isJsonArray()) {
            JsonArray items = node.getAsJsonArray();
            for (int i = 0; i < items.size(); i++) {
                collectUrls(items.get(i), urls);
            }
            return;
        }
        if (node.isJsonPrimitive() && node.getAsJsonPrimitive().isString()) {
            String value = node.getAsString();
            if (value.startsWith("http://") || value.startsWith("https://")) {
                urls.add(value);
            }
            return;
        }
        if (!node.isJsonObject()) {
            return;
        }
        JsonObject item = node.getAsJsonObject();
        if (item.has("url") && item.get("url").isJsonPrimitive()) {
            urls.add(item.get("url").getAsString());
        } else if (item.has("image_url") && item.get("image_url").isJsonPrimitive()) {
            urls.add(item.get("image_url").getAsString());
        }
    }
}
