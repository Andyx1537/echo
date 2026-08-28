package com.echo.harness;

/**
 * 羁绊温度演化模型（PRD §3.12 演化公式）。
 *
 * <p>{@code ΔT = −kDecay·(T−floor)·欠维系比 + healRate·(100−T)·盈余比}，结果 clamp 到 [floor, 100]。</p>
 *
 * <ul>
 *   <li>每宠暖意 &lt; c → 欠维系：欠维系比 = (c − heartsPerPet)/c ∈ (0,1]，朝地板缓慢回落；</li>
 *   <li>每宠暖意 = c → 恰好维系：两比皆 0，温度不变（"没有就不变了"）；</li>
 *   <li>每宠暖意 &gt; c → 盈余：盈余比 = min((heartsPerPet − c)/c, 1) ∈ (0,1]，朝 100 回暖。</li>
 * </ul>
 */
public final class TemperatureModel {

    private TemperatureModel() {
    }

    /**
     * 单步温度演化。
     *
     * @param t            当前温度（会被 clamp 到 [floor,100] 参与计算）
     * @param heartsPerPet 当日每宠投入暖意（爱心）
     * @param cfg          数值配置
     * @return 演化后的温度，clamp 到 [floor, 100]
     */
    public static double step(double t, double heartsPerPet, NumericConfig cfg) {
        double floor = cfg.floor();
        double clampedT = clamp(t, floor);
        double c = cfg.c();

        double shortFrac;
        double surplusFrac;
        if (heartsPerPet < c) {
            shortFrac = c <= 0 ? 0.0 : (c - heartsPerPet) / c;
            surplusFrac = 0.0;
        } else {
            shortFrac = 0.0;
            surplusFrac = c <= 0 ? 0.0 : Math.min((heartsPerPet - c) / c, 1.0);
        }

        double delta = -cfg.kDecay() * (clampedT - floor) * shortFrac
                + cfg.healRate() * (NumericConfig.CEILING - clampedT) * surplusFrac;

        return clamp(clampedT + delta, floor);
    }

    private static double clamp(double t, double floor) {
        if (t < floor) {
            return floor;
        }
        return Math.min(t, NumericConfig.CEILING);
    }
}
