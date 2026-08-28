package com.echo.http.exposure;

import com.echo.http.ApiException;
import com.echo.http.Json;
import com.echo.http.RequestContext;
import com.echo.http.Router;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 曝光上报端点（{@code TECH-DESIGN-feed-recall-and-exposure §3.10.3}，前置闸门 {@code G-2}）。
 *
 * <p>{@code POST /plaza/impressions}。响应固定 {@code {accepted, rejected}}，
 * 🔴 <b>不返回错误细节</b>——否则等于给刷量者一个"哪条被拦了、为什么"的反馈信号，
 * 让他可以逐步试出五道校验的边界。</p>
 */
@Slf4j
public final class ImpressionApi {

    private final ExposureRecorder recorder;

    public ImpressionApi(ExposureRecorder recorder) {
        this.recorder = recorder;
    }

    public void register(Router r) {
        r.add("POST", "/plaza/impressions", this::report);
    }

    private Object report(RequestContext ctx) {
        JsonObject body = ctx.body();
        String reqId = Json.getString(body, "reqId", "");
        if (!body.has("items") || !body.get("items").isJsonArray()) {
            throw new ApiException(ApiException.BAD_PARAM, "上报格式不对。", "items must be array");
        }
        JsonArray raw = body.getAsJsonArray("items");

        List<ExposureRecorder.Item> items = new ArrayList<>(raw.size());
        for (JsonElement el : raw) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject item = el.getAsJsonObject();
            String cardId = Json.getString(item, "cardId", "");
            if (cardId.isBlank()) {
                continue;
            }
            // 🔴 只读这四个字段。viaBoost / channel / pool / slotProvenance / viewerId 即便前端传了也忽略，
            //    它们是排序侧的归属信息，一律从服务端 reqId 快照取。
            items.add(new ExposureRecorder.Item(
                    cardId,
                    (int) asLong(item, "pos"),
                    asLong(item, "dwellMs"),
                    asLong(item, "ts")));
        }

        // viewerId 取 token 解出的账号，不取请求体
        ExposureRecorder.Outcome outcome = recorder.record(reqId, ctx.accountId(), items);
        Map<String, Object> data = Json.map();
        data.put("accepted", outcome.accepted());
        data.put("rejected", outcome.rejected());
        return data;
    }

    private static long asLong(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return o.get(key).getAsLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
