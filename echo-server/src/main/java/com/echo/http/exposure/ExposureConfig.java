package com.echo.http.exposure;

import lombok.extern.slf4j.Slf4j;

/**
 * 曝光记账配置（{@code TECH-DESIGN-feed-recall-and-exposure §3.9 / §3.10.4}）。
 *
 * <p>全部来自环境变量，默认值即规格给的值。</p>
 */
@Slf4j
public record ExposureConfig(
        /* 曝光记账总开关（关 → 排序层按"额度无限"运行，仅联调）。 */
        boolean enabled,
        /* reqId 下发快照的存活时长（秒）。 */
        int reqTtlSeconds,
        /* reqId 快照的条数上限（LRU）。 */
        int reqMax,
        /* 单 viewer 每分钟允许的曝光条数。 */
        int rateLimitPerMinute,
        /* 单请求携带的曝光条数上限，超出部分丢弃。 */
        int batchMax,
        /* 异步落库缓冲区上限；满了丢最旧（丢失方向与北极星同向，见 §3.10.6）。 */
        int flushQueueMax,
        /* 异步落库的攒批条数与间隔（毫秒）。 */
        int flushBatchSize,
        long flushIntervalMs) {

    public static ExposureConfig fromEnv() {
        return new ExposureConfig(
                envBool("ECHO_EXPOSURE_ENABLED", true),
                envInt("ECHO_IMPRESSION_REQ_TTL_SEC", 1800),
                envInt("ECHO_IMPRESSION_REQ_MAX", 50_000),
                envInt("ECHO_IMPRESSION_RATE_LIMIT", 200),
                envInt("ECHO_IMPRESSION_BATCH_MAX", 50),
                envInt("ECHO_IMPRESSION_FLUSH_QUEUE_MAX", 100_000),
                envInt("ECHO_IMPRESSION_FLUSH_BATCH", 500),
                envInt("ECHO_IMPRESSION_FLUSH_INTERVAL_MS", 1000));
    }

    /** 单测/联调用的紧凑默认值。 */
    public static ExposureConfig forTest() {
        return new ExposureConfig(true, 1800, 1000, 200, 50, 1000, 100, 50);
    }

    private static int envInt(String key, int def) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("{} 不是数字（{}），用默认值 {}", key, raw, def);
            return def;
        }
    }

    private static boolean envBool(String key, boolean def) {
        String raw = System.getenv(key);
        return raw == null || raw.isBlank() ? def : Boolean.parseBoolean(raw.trim());
    }
}
