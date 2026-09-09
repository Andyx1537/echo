package com.echo.http;

import com.echo.http.onboarding.OnboardingApi;
import com.echo.http.auth.AuthPrincipal;
import com.echo.http.auth.SessionAuthenticator;

import com.echo.infra.storage.IStorage;
import com.echo.infra.storage.LocalDiskStorage;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 轻量 HTTP/JSON REST 网关（H5 核心闭环）。
 *
 * <p><b>HTTP 库技术选择</b>：采用 JDK 自带 {@code com.sun.net.httpserver.HttpServer}，
 * <em>零新增依赖</em>、随 JVM 可用，契合任务"轻量内嵌 HTTP、避免引入重框架"的要求；
 * JSON 复用工程已有的 Gson。它足以承载 H5 核心闭环的 REST 端点；若未来需要 HTTP/2、
 * 大并发或过滤器链，再评估替换为 Netty（引擎已具备）或独立框架。</p>
 *
 * <p>职责：绑定端口 → 解析请求(方法/路径/查询/体) → 统一鉴权（Bearer token→accountId，
 * 仅 {@code POST /auth/guest} 免鉴权）→ 交给 {@link Router} 命中的 {@link Route} →
 * 成功包 {@code {code:0,data}}、异常包 {@code {code,msg,detail}}（错误文案过词表）。</p>
 *
 * <p>与既有 WebSocket 9001 完全隔离：本网关只用注入的 {@link EchoApi} 服务与内存/占位数据，
 * 不触碰 WS 协议层与 harness。</p>
 */
@Slf4j
public final class HttpGateway {

    /** 契约 Base Path。 */
    public static final String BASE_PATH = "/api/v1";

    /** 素材上传/下发上限：25MB（防止超大体拖垮内存态读取）。 */
    private static final int MAX_UPLOAD_BYTES = 25 * 1024 * 1024;

    private final int port;
    private final Router router;
    private final com.echo.http.store.EchoStore store;
    private final IStorage storage;
    /** 素材归属。上传时落一条，发布时据此判 mediaKey 是不是本人的。 */
    private final com.echo.http.work.ResourceStore resources;
    private final com.echo.http.onboarding.OnboardingApi onboarding;
    private final ExecutorService executor;
    private final SessionAuthenticator sessions;
    private HttpServer server;

    public HttpGateway(int port, Router router, com.echo.http.store.EchoStore store,
                       IStorage storage, com.echo.http.work.ResourceStore resources,
                       ExecutorService executor,
                       com.echo.http.onboarding.OnboardingApi onboarding) {
        this(port, router, store, storage, resources, executor, onboarding, null);
    }

    public HttpGateway(int port, Router router, com.echo.http.store.EchoStore store,
                       IStorage storage, com.echo.http.work.ResourceStore resources,
                       ExecutorService executor, com.echo.http.onboarding.OnboardingApi onboarding,
                       SessionAuthenticator sessions) {
        this.port = port;
        this.router = router;
        this.store = store;
        this.storage = storage;
        this.resources = resources;
        this.executor = executor;
        this.onboarding = onboarding;
        this.sessions = sessions;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(BASE_PATH, this::dispatch);
        // 健康检查（不带 base path，便于 LB/联调探活）
        server.createContext("/healthz", exchange -> {
            byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(b);
            }
        });
        // 素材下发（公开，供 <img>/<audio>/<video> 直接引用；最长前缀匹配优先于 /api/v1）
        server.createContext(LocalDiskStorage.DOWNLOAD_PREFIX, this::serveFile);
        if (executor != null) {
            server.setExecutor(executor);
        }
        server.start();
        log.info("EchoServer HTTP 网关已启动: http://0.0.0.0:{}{} (health: /healthz)", port, BASE_PATH);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Actual bound port, including the OS-assigned port when constructed with {@code 0}. */
    public int localPort() {
        if (server == null) {
            throw new IllegalStateException("HTTP gateway has not started");
        }
        return server.getAddress().getPort();
    }

    private void dispatch(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String rawPath = exchange.getRequestURI().getPath();
        String path = rawPath.startsWith(BASE_PATH) ? rawPath.substring(BASE_PATH.length()) : rawPath;
        String logPath = path;
        if (path.isEmpty()) {
            path = "/";
        }
        try {
            if ("OPTIONS".equalsIgnoreCase(method)) {
                writeCors(exchange);
                write(exchange, 204, "");
                return;
            }
            // 素材上传：走原始字节 + multipart 解析（绕开 JSON body 读取，二进制安全）
            if ("POST".equalsIgnoreCase(method) && "/upload".equals(path)) {
                handleUpload(exchange);
                return;
            }
            if ("POST".equalsIgnoreCase(method) && path.matches("/pet/onboarding/[^/]+/assets")) {
                handleOnboardingUpload(exchange, path);
                return;
            }
            if ("GET".equalsIgnoreCase(method)
                    && path.matches("/pet/onboarding/[^/]+/assets/[^/]+/content")) {
                handleOnboardingAsset(exchange, path);
                return;
            }
            Router.Match match = router.match(method, path);
            if (match == null) {
                if (router.pathExists(path)) {
                    writeError(exchange, 405, ApiException.BAD_PARAM, "这个动作暂时用不了，换个方式试试？", "method not allowed");
                } else {
                    writeError(exchange, 404, ApiException.NOT_FOUND, "这里还空着，没找到你要的内容。", "not found: " + path);
                }
                return;
            }
            // Dynamic segments may contain credentials (for example resolutionToken).
            // Logs and metrics must use the registered template, never the request value.
            logPath = match.routeTemplate();

            AuthPrincipal principal = null;
            if (!match.entry.isPublic) {
                principal = authenticate(exchange);
            }

            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            JsonObject body = readBody(exchange);
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!values.isEmpty()) headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0));
            });
            String clientIp = exchange.getRemoteAddress() == null ? "unknown"
                    : exchange.getRemoteAddress().getAddress().getHostAddress();
            RequestContext ctx = new RequestContext(method, match.pathParams, query, body, principal, headers, clientIp);

            Object data = match.entry.route.handle(ctx);
            if (data instanceof HttpResult result) {
                writeOk(exchange, result.status(), result.data());
            } else {
                writeOk(exchange, data);
            }
        } catch (ApiException e) {
            log.debug("业务异常 code={}, path={}, detail={}", e.code(), logPath, e.detail());
            int http = httpStatusOf(e.code());
            writeError(exchange, http, e.code(), e.getMessage(), e.detail(), e.data());
        } catch (Exception e) {
            log.error("网关处理异常 path={}", logPath, e);
            writeError(exchange, 500, ApiException.SERVER_ERROR,
                    "这里出了点小状况，待会儿再来看看它好吗？", e.getClass().getSimpleName());
        }
    }

    /**
     * 素材上传：鉴权 → 🔴 {@code S1′} 绑定校验 → 读原始字节 → 解析 multipart → 存储 →
     * 返回 {@code {resourceId,url}}。
     *
     * <p>🔴 <b>绑定校验必须在读请求体之前</b>：上传是唯一一条「先花掉资源再判权限」会真出问题的路径，
     * 25MB 的体读完再拒等于把带宽和内存白送出去，而游客是无限身份。</p>
     *
     * <p>⚠️ 这个端点<b>不在 {@link Router} 里</b>（要原始字节 + multipart 解析），所以它不会被
     * 任何按路由表做的审查扫到。{@code S1′} 点名的「上传素材」就落在这里，别处没有第二个上传口。</p>
     */
    private void handleUpload(HttpExchange exchange) throws IOException {
        long accountId = authenticate(exchange).accountId();
        if (!BindingGuard.isBound(store, accountId)) {
            writeError(exchange, 403, ApiException.BINDING_REQUIRED, BindingGuard.COPY_DEFAULT,
                    BindingGuard.detail(store, accountId, "upload"));
            return;
        }
        byte[] body;
        try (InputStream is = exchange.getRequestBody()) {
            body = is.readNBytes(MAX_UPLOAD_BYTES + 1);
        }
        if (body.length > MAX_UPLOAD_BYTES) {
            writeError(exchange, 413, ApiException.BAD_PARAM,
                    "这份素材有点大，换张小一些的再试试？", "upload exceeds " + MAX_UPLOAD_BYTES + " bytes");
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        MultipartParser.FilePart part = MultipartParser.parseFirstFile(body, contentType);
        byte[] data;
        String ct;
        String filename;
        if (part != null) {
            data = part.data();
            ct = part.contentType();
            filename = part.filename();
        } else {
            // 兜底：非 multipart（直接把文件当请求体发）也能收下
            data = body;
            ct = contentType;
            filename = exchange.getRequestURI().getRawQuery() == null ? null
                    : parseQuery(exchange.getRequestURI().getRawQuery()).get("filename");
        }
        if (data.length == 0) {
            writeError(exchange, 400, ApiException.BAD_PARAM, "好像没有选到素材呢。", "empty upload");
            return;
        }
        String resourceId = String.valueOf(java.util.UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        IStorage.Stored stored = storage.put(resourceId, data, ct, filename);
        // 🔴 归属必须落库，此前这里只有一行日志——日志查不了，于是 POST /works
        //    无从判断 mediaKey 是不是本人的（SPEC-security §4.14 E4）。
        //    记不上就拒绝：放行等于给出一条「把写入打挂 → 后续 key 全部无主」的路。
        if (!resources.record(stored.resourceId(), accountId, stored.key(), ct, data.length,
                System.currentTimeMillis())) {
            writeError(exchange, 500, ApiException.SERVER_ERROR,
                    "素材没能存好，再试一次好吗？", "resource ownership not recorded");
            return;
        }
        log.info("[upload] accountId={}, key={}, size={}B", accountId, stored.key(), data.length);
        writeOk(exchange, Map.of("resourceId", stored.resourceId(), "url", stored.url()));
    }

    private void handleOnboardingUpload(HttpExchange exchange, String path) throws IOException {
        if (onboarding == null) {
            writeError(exchange, 503, ApiException.SERVER_ERROR, "建档上传暂时不可用。", "onboarding_unavailable");
            return;
        }
        long accountId = authenticate(exchange).accountId();
        byte[] body = exchange.getRequestBody().readNBytes(MAX_UPLOAD_BYTES + 1);
        if (body.length > MAX_UPLOAD_BYTES) {
            writeError(exchange, 413, ApiException.BAD_PARAM, "这份素材有点大，换一份小一些的试试。", "asset_upload_incomplete");
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        MultipartParser.FilePart file = MultipartParser.parseFirstFile(body, contentType);
        Map<String, String> fields = MultipartParser.parseTextFields(body, contentType);
        if (file == null || file.data().length == 0) {
            writeError(exchange, 400, ApiException.BAD_PARAM, "没有读到这份素材，请再选一次。", "asset_upload_incomplete");
            return;
        }
        String mediaType = fields.get("mediaType");
        long expectedVersion;
        try {
            expectedVersion = Long.parseLong(fields.getOrDefault("expectedSessionVersion", ""));
        } catch (NumberFormatException e) {
            writeError(exchange, 409, ApiException.RULE_FORBIDDEN, "建档进度需要刷新后再上传。", "onboarding_version_conflict");
            return;
        }
        String onboardingId = path.substring("/pet/onboarding/".length(), path.length() - "/assets".length());
        String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        String requestFingerprint;
        try {
            requestFingerprint = OnboardingApi.uploadFingerprint(onboardingId, expectedVersion, mediaType, file.data());
            Map<String, Object> replay = onboarding.replayUploadedAsset(
                    accountId, onboardingId, idempotencyKey, requestFingerprint);
            if (replay != null) {
                writeOk(exchange, replay);
                return;
            }
            onboarding.assertUploadAllowed(accountId, onboardingId, expectedVersion, mediaType);
        } catch (ApiException e) {
            writeError(exchange, httpStatusOf(e.code()), e.code(), e.getMessage(), e.detail(), e.data());
            return;
        }
        String declaredPrefix = "image".equalsIgnoreCase(mediaType) ? "image/"
                : "video".equalsIgnoreCase(mediaType) ? "video/" : "";
        if (declaredPrefix.isEmpty() || file.contentType() == null
                || !file.contentType().toLowerCase(java.util.Locale.ROOT).startsWith(declaredPrefix)) {
            writeError(exchange, 400, ApiException.BAD_PARAM,
                    "这份素材的类型没有识别出来，请重新选择。", "asset_upload_incomplete");
            return;
        }
        String resourceId = java.util.UUID.nameUUIDFromBytes(
                (onboardingId + ":" + idempotencyKey + ":" + requestFingerprint)
                        .getBytes(StandardCharsets.UTF_8)).toString();
        IStorage.Stored stored = storage.put(resourceId, file.data(), file.contentType(), file.filename());
        if (!resources.record(stored.resourceId(), accountId, stored.key(), file.contentType(), file.data().length,
                System.currentTimeMillis())) {
            writeError(exchange, 500, ApiException.SERVER_ERROR, "素材没能存好，请再试一次。", "asset_upload_incomplete");
            return;
        }
        try {
            Map<String, Object> result = onboarding.attachUploadedAsset(accountId,
                    onboardingId, idempotencyKey, expectedVersion,
                    stored.resourceId(), mediaType, requestFingerprint);
            writeOk(exchange, result);
        } catch (ApiException e) {
            boolean queued = resources.revoke(stored.resourceId(), accountId, System.currentTimeMillis());
            log.warn("[onboarding-upload] attach failed; orphan queued={}, resourceId={}, detail={}",
                    queued, stored.resourceId(), e.detail());
            writeError(exchange, httpStatusOf(e.code()), e.code(), e.getMessage(), e.detail(), e.data());
        }
    }

    private void handleOnboardingAsset(HttpExchange exchange, String path) throws IOException {
        if (onboarding == null) {
            writeError(exchange, 503, ApiException.SERVER_ERROR, "建档素材暂时不可用。", "onboarding_unavailable");
            return;
        }
        long accountId = authenticate(exchange).accountId();
        String[] parts = path.split("/");
        String resourceId = onboarding.ownedAssetResource(accountId, parts[3], parts[5]);
        IStorage.Loaded loaded = storage.loadByResourceId(resourceId);
        if (loaded == null) {
            writeError(exchange, 404, ApiException.NOT_FOUND, "这份素材暂时找不到。", "onboarding_not_found");
            return;
        }
        writeCors(exchange);
        exchange.getResponseHeaders().set("Content-Type", loaded.contentType());
        exchange.getResponseHeaders().set("Cache-Control", "private, max-age=300");
        exchange.sendResponseHeaders(200, loaded.data().length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(loaded.data());
        }
    }

    /** 素材下发：公开读取本地存储对象（跨源用绝对 url，同源用相对 /api/v1/files/）。 */
    private void serveFile(HttpExchange exchange) {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                write(exchange, 405, "");
                return;
            }
            String rawPath = exchange.getRequestURI().getPath();
            String key = rawPath.substring(rawPath.lastIndexOf('/') + 1);
            IStorage.Loaded loaded = storage.load(key);
            if (loaded == null) {
                write(exchange, 404, "");
                return;
            }
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Content-Type", loaded.contentType());
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            exchange.sendResponseHeaders(200, loaded.data().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(loaded.data());
            }
        } catch (Exception e) {
            log.warn("素材下发失败", e);
            try {
                write(exchange, 500, "");
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    /** 解析 Bearer token → accountId；缺失/无效抛 1001。 */
    private AuthPrincipal authenticate(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new ApiException(ApiException.UNAUTHORIZED, "先在门口取一张通行证吧（游客也可以）。", "missing bearer token");
        }
        String token = auth.substring("Bearer ".length()).trim();
        AuthPrincipal principal;
        if (sessions != null) {
            principal = sessions.authenticate(token);
        } else {
            Long accountId = store.resolveToken(token);
            principal = accountId == null ? null : new AuthPrincipal(accountId, null, "legacy", null);
        }
        if (principal == null) {
            throw new ApiException(ApiException.UNAUTHORIZED, "通行证过期了，重新取一张就好。", "invalid token");
        }
        return principal;
    }

    private static int httpStatusOf(int code) {
        // 🔴 S1′ 的绑定拦截必须是 403，不能落到下面那条「1xxx → 401」上：
        //    游客是已鉴权的，401 会触发前端既有的「token 失效 → 重新取通行证」逻辑，
        //    于是清掉游客 token、再发一张、再被拦 —— 一个用户看不懂的循环。
        //    见 ApiException.BINDING_REQUIRED。
        if (code == ApiException.BINDING_REQUIRED) {
            return 403;
        }
        if (code >= 1000 && code < 2000) {
            return 401;
        }
        if (code >= 2000 && code < 3000) {
            if (code == ApiException.NOT_FOUND) return 404;
            if (code == ApiException.GONE) return 410;
            return 400;
        }
        if (code >= 3000 && code < 4000) {
            return 409;
        }
        return 500;
    }

    private JsonObject readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return Json.parseObject(body);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                map.put(decode(pair), "");
            } else {
                map.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return map;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private void writeOk(HttpExchange exchange, Object data) throws IOException {
        writeOk(exchange, 200, data);
    }

    private void writeOk(HttpExchange exchange, int status, Object data) throws IOException {
        writeCors(exchange);
        write(exchange, status, Json.ok(data));
    }

    private void writeError(HttpExchange exchange, int httpStatus, int code, String msg, String detail) {
        writeError(exchange, httpStatus, code, msg, detail, null);
    }

    private void writeError(HttpExchange exchange, int httpStatus, int code, String msg, String detail, Object data) {
        try {
            writeCors(exchange);
            write(exchange, httpStatus, Json.error(code, CopyGuardFilter.sanitize(msg), detail, data));
        } catch (IOException io) {
            log.warn("写错误响应失败", io);
        }
    }

    private void writeCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type, Idempotency-Key");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (status == 204 || bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
