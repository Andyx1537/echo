package com.echo.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量 JSON 工具：请求体解析 + 契约成功/错误信封构造。
 *
 * <p>直接用工程已有依赖 Gson（{@code com.google.code.gson}），不引入任何新 HTTP/JSON 框架，
 * 保持"轻量 HTTP/JSON 网关"目标。成功信封 {@code {code:0,data}}、错误信封 {@code {code,msg,detail}}
 * 对齐 API-CONTRACT §0。</p>
 */
public final class Json {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private Json() {
    }

    /** 解析请求体为 JsonObject；空体返回空对象。 */
    public static JsonObject parseObject(String body) {
        if (body == null || body.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement el = JsonParser.parseString(body);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            throw new ApiException(ApiException.BAD_PARAM, "这段内容我没读懂，换个方式再试试？", "invalid json body");
        }
    }

    public static String getString(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    public static String requireString(JsonObject o, String key) {
        String v = getString(o, key, null);
        if (v == null || v.isBlank()) {
            throw new ApiException(ApiException.BAD_PARAM, "还差一点信息（" + key + "），补上就好。", "missing field: " + key);
        }
        return v;
    }

    public static int getInt(JsonObject o, String key, int def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def;
    }

    public static boolean getBool(JsonObject o, String key, boolean def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : def;
    }

    /** 成功信封。 */
    public static String ok(Object data) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("code", 0);
        env.put("data", data);
        return GSON.toJson(env);
    }

    /** 错误信封（msg 已在 {@link ApiException} 里过词表）。 */
    public static String error(int code, String msg, String detail) {
        return error(code, msg, detail, null);
    }

    public static String error(int code, String msg, String detail, Object data) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("code", code);
        env.put("msg", msg);
        if (detail != null) {
            env.put("detail", detail);
        }
        if (data != null) {
            env.put("data", data);
        }
        return GSON.toJson(env);
    }

    public static String toJson(Object o) {
        return GSON.toJson(o);
    }

    /** 便捷 map 构造。 */
    public static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }
}
