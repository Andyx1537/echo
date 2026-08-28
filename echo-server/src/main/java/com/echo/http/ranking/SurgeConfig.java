package com.echo.http.ranking;

import java.time.Duration;
import java.util.function.Function;

/**
 * {@code SURGE} 热度突增召回的配置（{@code SPEC-recommendation-ranking §3.9} /
 * {@code TECH-DESIGN §8.6}）。
 *
 * <ul>
 *   <li>{@code ECHO_SURGE_ABS_FLOOR} —— 绝对地板，近 24h 独立互动者数下限，默认 {@code 5}</li>
 *   <li>{@code ECHO_SURGE_RATIO} —— 相对增速倍数，默认 {@code 3.0}</li>
 *   <li>{@code ECHO_SURGE_TTL_HOURS} —— 拉回主动分发的时长，默认 {@code 24}</li>
 * </ul>
 *
 * <p>终身触发上限与冷却期不做成可配：它们是<b>防刷的结构性上限</b>，
 * 一旦可配就一定会在某次"临时调一下"里被放开。</p>
 */
public record SurgeConfig(int absFloor, double ratio, int ttlHours) {

    /** 🔴 终身触发上限：一张卡这辈子最多被拉回 2 次。 */
    public static final int LIFETIME_MAX_TRIGGERS = 2;
    /** 🔴 同一张卡两次触发之间的冷却期。 */
    public static final Duration COOLDOWN = Duration.ofDays(30);
    /** 🔴 同一作者名下的内容，每 7 天最多触发 1 次。 */
    public static final Duration AUTHOR_WINDOW = Duration.ofDays(7);
    /** 🔴 互动者里与作者有关注关系的占比超过这个数，本次触发作废。 */
    public static final double MAX_FOLLOW_LINKED_SHARE = 0.50;

    public static final SurgeConfig DEFAULTS = new SurgeConfig(5, 3.0, 24);

    public SurgeConfig {
        if (absFloor < 1) {
            throw new IllegalStateException("配置非法：ECHO_SURGE_ABS_FLOOR 至少为 1：" + absFloor);
        }
        if (ratio <= 1.0) {
            // ratio ≤ 1 等于「不比从前热也算突增」，SURGE 就成了给所有流掉内容的普惠救济，
            // 而普惠救济（REVIVE）已经被整体移除
            throw new IllegalStateException("配置非法：ECHO_SURGE_RATIO 必须大于 1：" + ratio);
        }
        if (ttlHours < 1) {
            throw new IllegalStateException("配置非法：ECHO_SURGE_TTL_HOURS 至少为 1：" + ttlHours);
        }
    }

    public static SurgeConfig fromEnv() {
        return from(System::getenv);
    }

    public static SurgeConfig from(Function<String, String> env) {
        return new SurgeConfig(
                (int) num(env, "ECHO_SURGE_ABS_FLOOR", DEFAULTS.absFloor),
                num(env, "ECHO_SURGE_RATIO", DEFAULTS.ratio),
                (int) num(env, "ECHO_SURGE_TTL_HOURS", DEFAULTS.ttlHours));
    }

    private static double num(Function<String, String> env, String key, double def) {
        String v = env.apply(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置非法：" + key + " 不是数字：" + v, e);
        }
    }
}
