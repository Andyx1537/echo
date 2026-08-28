package com.echo.http.ranking;

import java.util.function.Function;

/**
 * 分发权重模型的配置（{@code TECH-DESIGN-feed-recall-and-exposure §8.9.3}）。
 *
 * <p>默认值全部照抄规格，不自行取整、不"顺手调优"。改这里的任何一个数都是改产品分发行为，
 * 应当先改规格。</p>
 *
 * <ul>
 *   <li>{@code ECHO_WEIGHT_GAMMA} —— 单次投放衰减因子，默认 {@code 0.97}</li>
 *   <li>{@code ECHO_WEIGHT_TAU1_HOURS} / {@code ECHO_WEIGHT_TAU2_HOURS} —— 时间衰减两段的时间常数，默认 {@code 72} / {@code 36}</li>
 *   <li>{@code ECHO_WEIGHT_SEGMENT_HOURS} —— 分段点，默认 {@code 72}</li>
 *   <li>{@code ECHO_WEIGHT_FAIR_FLOOR} —— 欠投地板 {@code T_FLOOR}，默认 {@code 0.15}</li>
 *   <li>{@code ECHO_WEIGHT_MIN_RATIO} —— {@code W_MIN = ratio × W0}，默认 {@code 0.05}</li>
 *   <li>{@code ECHO_WEIGHT_DMIN_CLAMP} —— {@code D_min} 的钳位区间，默认 {@code 20,100}</li>
 * </ul>
 */
public record WeightConfig(
        double gamma,
        double tau1Hours,
        double tau2Hours,
        double segmentHours,
        double fairFloor,
        double minRatio,
        int dMinLow,
        int dMinHigh) {

    public static final WeightConfig DEFAULTS =
            new WeightConfig(0.97, 72.0, 36.0, 72.0, 0.15, 0.05, 20, 100);

    /**
     * 🔴 启动校验：{@code MIN_RATIO} 必须小于 {@code FAIR_FLOOR}。
     *
     * <p>默认是 {@code 0.05 < 0.15}。若反过来，欠投地板 {@code T_FLOOR} 撑起来的权重会低于
     * 退场线 {@code W_MIN}，于是一张<b>正在被地板保护的欠投卡</b>会同时满足退出首页的条件——
     * 扶持与退场互相打架，而表现出来只是「新卡莫名其妙推不动」，从日志里看不出原因。
     * 与其让它在线上以这种形式发作，不如起不来。</p>
     */
    public WeightConfig {
        if (!(minRatio < fairFloor)) {
            throw new IllegalStateException(
                    "配置非法：ECHO_WEIGHT_MIN_RATIO(" + minRatio + ") 必须小于 ECHO_WEIGHT_FAIR_FLOOR("
                            + fairFloor + ")，否则欠投地板托起的权重会低于退场线，扶持与退场互相打架");
        }
        if (!(gamma > 0 && gamma < 1)) {
            throw new IllegalStateException("配置非法：ECHO_WEIGHT_GAMMA 必须落在 (0,1)：" + gamma);
        }
        if (dMinLow > dMinHigh) {
            throw new IllegalStateException("配置非法：D_min 钳位区间上下界颠倒：" + dMinLow + "," + dMinHigh);
        }
    }

    public static WeightConfig fromEnv() {
        return from(System::getenv);
    }

    public static WeightConfig from(Function<String, String> env) {
        int[] clamp = parseClamp(env.apply("ECHO_WEIGHT_DMIN_CLAMP"));
        return new WeightConfig(
                num(env, "ECHO_WEIGHT_GAMMA", DEFAULTS.gamma),
                num(env, "ECHO_WEIGHT_TAU1_HOURS", DEFAULTS.tau1Hours),
                num(env, "ECHO_WEIGHT_TAU2_HOURS", DEFAULTS.tau2Hours),
                num(env, "ECHO_WEIGHT_SEGMENT_HOURS", DEFAULTS.segmentHours),
                num(env, "ECHO_WEIGHT_FAIR_FLOOR", DEFAULTS.fairFloor),
                num(env, "ECHO_WEIGHT_MIN_RATIO", DEFAULTS.minRatio),
                clamp[0], clamp[1]);
    }

    private static double num(Function<String, String> env, String key, double def) {
        String v = env.apply(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            // 配错了就起不来。回落到默认值等于「配置写了但没生效」，
            // 而分发行为的偏差要几天后看数据才发现得了
            throw new IllegalStateException("配置非法：" + key + " 不是数字：" + v, e);
        }
    }

    private static int[] parseClamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return new int[]{DEFAULTS.dMinLow, DEFAULTS.dMinHigh};
        }
        String[] parts = raw.split(",");
        if (parts.length != 2) {
            throw new IllegalStateException("配置非法：ECHO_WEIGHT_DMIN_CLAMP 应形如 \"20,100\"：" + raw);
        }
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置非法：ECHO_WEIGHT_DMIN_CLAMP 不是两个整数：" + raw, e);
        }
    }
}
