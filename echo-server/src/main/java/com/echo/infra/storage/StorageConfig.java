package com.echo.infra.storage;

import java.util.function.Function;

/**
 * 对象存储配置（环境变量装配）。
 *
 * <ul>
 *   <li>{@code ECHO_STORAGE_TYPE} —— local|oss|cos|minio（默认 local）</li>
 *   <li>{@code ECHO_STORAGE_DIR} —— local 落盘目录（默认 {@code <cwd>/data/uploads}）</li>
 *   <li>{@code ECHO_STORAGE_BASE_URL} —— 返回 url 的绝对前缀；空则用相对路径
 *       {@code /api/v1/files/<key>}（同源部署即可，跨源联调请设为后端源，如 http://127.0.0.1:8080）</li>
 *   <li>{@code ECHO_STORAGE_ENDPOINT/BUCKET/AK/SK/REGION} —— oss/cos/minio 预留（本期未接入）</li>
 * </ul>
 */
public record StorageConfig(
        String type,
        String localDir,
        String baseUrl,
        String endpoint,
        String bucket,
        String accessKey,
        String secretKey,
        String region) {

    public static StorageConfig fromEnv() {
        return from(System::getenv);
    }

    public static StorageConfig from(Function<String, String> env) {
        String type = blankToDefault(env.apply("ECHO_STORAGE_TYPE"), "local");
        String dir = blankToDefault(env.apply("ECHO_STORAGE_DIR"),
                System.getProperty("user.dir") + "/data/uploads");
        String baseUrl = blankToDefault(env.apply("ECHO_STORAGE_BASE_URL"), "");
        return new StorageConfig(
                type.toLowerCase(),
                dir,
                stripTrailingSlash(baseUrl),
                blankToDefault(env.apply("ECHO_STORAGE_ENDPOINT"), ""),
                blankToDefault(env.apply("ECHO_STORAGE_BUCKET"), ""),
                blankToDefault(env.apply("ECHO_STORAGE_AK"), ""),
                blankToDefault(env.apply("ECHO_STORAGE_SK"), ""),
                blankToDefault(env.apply("ECHO_STORAGE_REGION"), ""));
    }

    private static String blankToDefault(String v, String def) {
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static String stripTrailingSlash(String v) {
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }
}
