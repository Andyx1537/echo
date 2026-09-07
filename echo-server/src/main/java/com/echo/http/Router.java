package com.echo.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简路径路由：支持 {@code :param} 占位段（如 {@code /windows/:petId/flower}）。
 *
 * <p>纯字符串分段匹配，不引入任何 web 框架；配合 JDK 自带 {@code com.sun.net.httpserver.HttpServer}
 * 组成"轻量 HTTP 网关"。每条路由可标记是否公共（免鉴权，仅 {@code /auth/guest}）。</p>
 */
public final class Router {

    /** 单条路由定义。 */
    public static final class Entry {
        final String method;
        final String template;
        final String[] segments;
        final Route route;
        final boolean isPublic;

        Entry(String method, String path, Route route, boolean isPublic) {
            this.method = method;
            this.template = path;
            this.segments = split(path);
            this.route = route;
            this.isPublic = isPublic;
        }
    }

    /** 一次匹配结果：命中的路由 + 提取的路径参数。 */
    public static final class Match {
        public final Entry entry;
        public final Map<String, String> pathParams;

        Match(Entry entry, Map<String, String> pathParams) {
            this.entry = entry;
            this.pathParams = pathParams;
        }

        /** Execute the matched route without exposing the route entry internals. */
        public Object handle(RequestContext context) throws Exception {
            return entry.route.handle(context);
        }

        /** Whether the matched route intentionally bypasses bearer authentication. */
        public boolean isPublic() {
            return entry.isPublic;
        }

        /** Stable route template for metrics/logging; never contains decoded path parameters. */
        public String routeTemplate() {
            return entry.template;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public Router add(String method, String path, Route route) {
        entries.add(new Entry(method, path, route, false));
        return this;
    }

    public Router addPublic(String method, String path, Route route) {
        entries.add(new Entry(method, path, route, true));
        return this;
    }

    /** 匹配 method + path；未命中返回 null。 */
    public Match match(String method, String path) {
        String[] parts = split(path);
        for (Entry e : entries) {
            if (!e.method.equalsIgnoreCase(method)) {
                continue;
            }
            if (e.segments.length != parts.length) {
                continue;
            }
            Map<String, String> params = new HashMap<>();
            boolean ok = true;
            for (int i = 0; i < parts.length; i++) {
                String seg = e.segments[i];
                if (seg.startsWith(":")) {
                    params.put(seg.substring(1), urlDecode(parts[i]));
                } else if (!seg.equals(parts[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return new Match(e, params);
            }
        }
        return null;
    }

    /** 是否存在该 path 的其它方法（用于区分 404 与 405）。 */
    public boolean pathExists(String path) {
        String[] parts = split(path);
        for (Entry e : entries) {
            if (e.segments.length != parts.length) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < parts.length; i++) {
                String seg = e.segments[i];
                if (!seg.startsWith(":") && !seg.equals(parts[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    private static String[] split(String path) {
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty()) {
            return new String[0];
        }
        return p.split("/");
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
