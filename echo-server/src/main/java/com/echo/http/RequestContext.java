package com.echo.http;

import com.google.gson.JsonObject;
import com.echo.http.auth.AuthPrincipal;

import java.util.Map;

/**
 * 单次请求上下文：方法/路径参数/查询串/请求体 + 已鉴权账号。
 */
public final class RequestContext {

    private final String method;
    private final Map<String, String> pathParams;
    private final Map<String, String> query;
    private final JsonObject body;
    private final long accountId;
    private final Map<String, String> headers;
    private final AuthPrincipal principal;
    private final String clientIp;

    public RequestContext(String method, Map<String, String> pathParams,
                          Map<String, String> query, JsonObject body, long accountId) {
        this(method, pathParams, query, body, accountId, Map.of());
    }

    public RequestContext(String method, Map<String, String> pathParams,
                          Map<String, String> query, JsonObject body, long accountId,
                          Map<String, String> headers) {
        this.method = method;
        this.pathParams = pathParams;
        this.query = query;
        this.body = body;
        this.accountId = accountId;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.principal = accountId == 0 ? null : new AuthPrincipal(accountId, null, "legacy", null);
        this.clientIp = "unknown";
    }

    public RequestContext(String method, Map<String, String> pathParams, Map<String, String> query,
                          JsonObject body, AuthPrincipal principal, Map<String, String> headers, String clientIp) {
        this.method = method;
        this.pathParams = pathParams;
        this.query = query;
        this.body = body;
        this.principal = principal;
        this.accountId = principal == null ? 0L : principal.accountId();
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.clientIp = clientIp == null ? "unknown" : clientIp;
    }

    public String method() {
        return method;
    }

    public String path(String name) {
        return pathParams.get(name);
    }

    public String query(String name, String def) {
        String v = query.get(name);
        return v == null ? def : v;
    }

    /**
     * 读一个整数查询参数；<b>没传</b>时返回 {@code def}。
     *
     * <p>🔴 <b>传了但读不懂时抛 {@code BAD_PARAM}，不回落到 {@code def}。</b>
     * 回落看着宽容，实则最坑的是分页：客户端拿一个坏游标来，服务端安静地当成
     * {@code cursor=0} 返回第一页，客户端在「加载更多」语义下把第一页又追加一遍，
     * 拿到的 {@code nextCursor} 又指向第二页——它可以就这么一直转下去，
     * <b>两边都不会报错</b>。缺一页数据尚且能看出来，无声地重复一页看不出来。</p>
     *
     * <p>「没传」与「传了个读不懂的」是两件事：前者是「你没要求」，后者是「你要求了但我没懂」。
     * 只有前者该用默认值。</p>
     */
    public int queryInt(String name, int def) {
        String v = query.get(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(ApiException.BAD_PARAM,
                    "这一页没能翻过去，回到开头再看看吧。",
                    "query param '" + name + "' is not an integer: " + v);
        }
    }

    public JsonObject body() {
        return body;
    }

    /** 已鉴权的账号 ID（公共路由如 /auth/guest 时为 0）。 */
    public long accountId() {
        return accountId;
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    public AuthPrincipal principal() { return principal; }

    public String clientIp() { return clientIp; }
}
