package com.echo.infra.storage;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 本地磁盘存储（dev / 单机）。
 *
 * <p>字节写入 {@code baseDir/<key>}，key = {@code resourceId.<ext>}（扩展名由文件名/MIME 推断）。
 * 由 HTTP 网关 {@code GET /api/v1/files/{key}} 读回下发。防目录穿越：key 只允许安全字符。</p>
 */
@Slf4j
public class LocalDiskStorage implements IStorage {

    /** 网关下发路径前缀（与 HttpGateway 注册的 /files 上下文一致）。 */
    public static final String DOWNLOAD_PREFIX = "/api/v1/files/";

    private static final Map<String, String> EXT_MIME = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("webm", "video/webm"));

    private static final Map<String, String> MIME_EXT = Map.ofEntries(
            Map.entry("image/png", "png"),
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/gif", "gif"),
            Map.entry("image/webp", "webp"),
            Map.entry("audio/mpeg", "mp3"),
            Map.entry("audio/wav", "wav"),
            Map.entry("audio/mp4", "m4a"),
            Map.entry("video/mp4", "mp4"),
            Map.entry("video/quicktime", "mov"),
            Map.entry("video/webm", "webm"));

    private final Path baseDir;
    private final String urlPrefix;

    public LocalDiskStorage(String dir, String baseUrl) {
        this.baseDir = Path.of(dir).toAbsolutePath().normalize();
        // baseUrl 为空 → 相对路径（同源部署）；非空 → 绝对前缀（跨源联调）
        this.urlPrefix = (baseUrl == null ? "" : baseUrl) + DOWNLOAD_PREFIX;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建存储目录: " + baseDir, e);
        }
        log.info("LocalDiskStorage 就绪: dir={}, urlPrefix={}", baseDir, urlPrefix);
    }

    @Override
    public Stored put(String resourceId, byte[] data, String contentType, String filename) {
        String ext = pickExt(filename, contentType);
        String key = sanitize(resourceId) + (ext.isEmpty() ? "" : "." + ext);
        Path target = baseDir.resolve(key).normalize();
        if (!target.startsWith(baseDir)) {
            throw new IllegalArgumentException("非法存储键: " + key);
        }
        try {
            Files.write(target, data);
        } catch (IOException e) {
            throw new IllegalStateException("写入素材失败: " + key, e);
        }
        return new Stored(resourceId, key, urlPrefix + key);
    }

    @Override
    public Loaded load(String key) {
        String safe = sanitize(key);
        Path target = baseDir.resolve(safe).normalize();
        if (!target.startsWith(baseDir) || !Files.isRegularFile(target)) {
            return null;
        }
        try {
            byte[] data = Files.readAllBytes(target);
            return new Loaded(data, mimeOf(safe));
        } catch (IOException e) {
            throw new IllegalStateException("读取素材失败: " + key, e);
        }
    }

    /**
     * 按 resourceId 定位并读取：先按「resourceId 本身就是 key」试读，未命中再在目录内找
     * 主名（去扩展名）等于 resourceId 的文件——因为 {@code put} 时会按 MIME/文件名补扩展名，
     * 而上层只握有不带扩展名的 resourceId。
     */
    @Override
    public Loaded loadByResourceId(String resourceId) {
        String key = findKey(resourceId);
        return key == null ? null : load(key);
    }

    /**
     * 本地磁盘一律返回 {@code null}：{@code /api/v1/files/...} 只在同源/内网可达，
     * 交给外部供应商（如云视觉模型）拉取必然失败，由调用方回落 data-uri 内联。
     *
     * <p>仅当显式配置了<b>公网</b>绝对前缀（{@code ECHO_STORAGE_BASE_URL} 为 http(s) 且主机
     * 非 localhost/回环/私网）时，才认为外部可拉取。</p>
     */
    @Override
    public String externalUrl(String resourceId) {
        if (!isPublicPrefix(urlPrefix)) {
            return null;
        }
        String key = findKey(resourceId);
        return key == null ? null : urlPrefix + key;
    }

    /** 由 resourceId 反查真实存储键（带扩展名）；找不到返回 {@code null}。 */
    private String findKey(String resourceId) {
        String safe = sanitize(resourceId);
        if (safe.isEmpty()) {
            return null;
        }
        if (Files.isRegularFile(baseDir.resolve(safe).normalize())) {
            return safe;
        }
        try (Stream<Path> files = Files.list(baseDir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> safe.equals(stripExt(name)))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warn("按 resourceId 检索素材失败: resourceId={}, msg={}", safe, e.toString());
            return null;
        }
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 前缀是否为公网可达的绝对地址（排除回环/内网，那些地址外部供应商拉不到）。 */
    private static boolean isPublicPrefix(String prefix) {
        if (prefix == null || !(prefix.startsWith("http://") || prefix.startsWith("https://"))) {
            return false;
        }
        String host;
        try {
            host = URI.create(prefix).getHost();
        } catch (RuntimeException e) {
            return false;
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase();
        if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".localhost") || h.equals("::1")) {
            return false;
        }
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")) {
            return false;
        }
        return !h.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

    private static String pickExt(String filename, String contentType) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && dot < filename.length() - 1) {
                String ext = filename.substring(dot + 1).toLowerCase();
                if (ext.matches("[a-z0-9]{1,8}")) {
                    return ext;
                }
            }
        }
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            int semi = ct.indexOf(';');
            if (semi >= 0) {
                ct = ct.substring(0, semi).trim();
            }
            String ext = MIME_EXT.get(ct);
            if (ext != null) {
                return ext;
            }
        }
        return "";
    }

    private static String mimeOf(String key) {
        int dot = key.lastIndexOf('.');
        if (dot >= 0 && dot < key.length() - 1) {
            String ext = key.substring(dot + 1).toLowerCase();
            String mime = EXT_MIME.get(ext);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    /** 只保留安全字符，防目录穿越。 */
    private static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9._-]", "");
    }
}
