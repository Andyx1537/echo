package com.echo.http.auth;

import com.echo.http.ApiException;
import com.echo.http.RequestContext;
import com.echo.http.Router;
import com.google.gson.JsonObject;

/** HTTP adapter for phone-account-resolution-v1. */
public final class AuthApi {
    private final PgAuthService service;
    public AuthApi(PgAuthService service) { this.service = service; }

    public void register(Router router) {
        router.addPublic("POST", "/auth/device/session", this::deviceSession);
        router.addPublic("POST", "/auth/account/recovery/session", this::recover);
        router.add("POST", "/auth/phone/challenges", this::challenge);
        router.add("POST", "/auth/phone/challenges/:challengeId/verify", this::verify);
        router.addPublic("POST", "/auth/phone/resolutions/:resolutionToken/confirm", this::confirm);
    }

    public static void registerUnavailable(Router router) {
        com.echo.http.Route unavailable = ctx -> { throw new ApiException(ApiException.SERVER_ERROR,
                "身份服务暂时不可用，请稍后再试。", "auth_persistence_unavailable"); };
        router.addPublic("POST", "/auth/device/session", unavailable);
        router.addPublic("POST", "/auth/account/recovery/session", unavailable);
        router.add("POST", "/auth/phone/challenges", unavailable);
        router.add("POST", "/auth/phone/challenges/:challengeId/verify", unavailable);
        router.addPublic("POST", "/auth/phone/resolutions/:resolutionToken/confirm", unavailable);
    }

    private Object deviceSession(RequestContext ctx) {
        onlyKeys(ctx.body(), "deviceCredential", "bootstrapNonce");
        return service.deviceSession(optional(ctx.body(), "deviceCredential"), optional(ctx.body(), "bootstrapNonce"),
                ctx.header("Idempotency-Key"), ctx.clientIp());
    }

    private Object recover(RequestContext ctx) {
        onlyKeys(ctx.body(), "recoveryCredential");
        return service.recoverAnonymousSession(required(ctx.body(), "recoveryCredential"),
                ctx.header("Idempotency-Key"), ctx.clientIp());
    }

    private Object challenge(RequestContext ctx) {
        onlyKeys(ctx.body(), "phone", "purpose", "continuation");
        JsonObject continuation = object(ctx.body(), "continuation");
        onlyKeys(continuation, "intent", "resourceId", "schemaVersion");
        return service.createChallenge(ctx.principal(), required(ctx.body(), "phone"), required(ctx.body(), "purpose"),
                required(continuation, "intent"), optional(continuation, "resourceId"),
                optional(continuation, "schemaVersion"), ctx.header("Idempotency-Key"), ctx.clientIp());
    }

    private Object verify(RequestContext ctx) {
        onlyKeys(ctx.body(), "code");
        return service.verify(ctx.principal(), ctx.path("challengeId"), required(ctx.body(), "code"),
                ctx.header("Idempotency-Key"));
    }

    private Object confirm(RequestContext ctx) {
        onlyKeys(ctx.body());
        String authorization = ctx.header("Authorization");
        String bearer = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim() : null;
        return service.confirm(bearer, ctx.path("resolutionToken"), ctx.header("Idempotency-Key"));
    }

    private static JsonObject object(JsonObject body, String name) {
        if (body.has(name) && body.get(name).isJsonObject()) return body.getAsJsonObject(name);
        throw new ApiException(ApiException.BAD_PARAM, "请求信息不完整，请检查后再试。", "continuation_invalid");
    }

    private static String required(JsonObject body, String name) {
        String value = optional(body, name);
        if (value == null || value.isBlank()) throw new ApiException(ApiException.BAD_PARAM,
                "请求信息不完整，请检查后再试。", "missing " + name);
        return value;
    }

    private static String optional(JsonObject body, String name) {
        return body.has(name) && !body.get(name).isJsonNull() ? body.get(name).getAsString() : null;
    }

    private static void onlyKeys(JsonObject body, String... allowed) {
        java.util.Set<String> expected = java.util.Set.of(allowed);
        if (!expected.containsAll(body.keySet())) {
            throw new ApiException(ApiException.BAD_PARAM, "请求里包含不能识别的信息，请返回后重试。",
                    "continuation_invalid");
        }
    }
}
