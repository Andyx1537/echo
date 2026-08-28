package com.echo.harness;

/**
 * 羁绊温度 &amp; 爱心经济数值配置（PRD §3.12 数值体系）。
 *
 * <p>体验 Bot 内循环围绕这一组参数做验证：调参 → 跑 bot 群体 → 看分群反馈 → 收敛。
 * 单位与含义严格对齐 §3.12 与 EXP-BOTS §8.2。</p>
 *
 * @param floor        温度地板（默认 60）
 * @param c            每宠每日维系成本（❤/日，默认 20）
 * @param capFree      白嫖每日爱心上限（❤/日，默认 20）
 * @param basicGrant   基础档额外爱心（❤/日，默认 20）
 * @param premiumGrant 高级档额外爱心（❤/日，默认 80）
 * @param kDecay       无维系日衰减系数（/日，默认 0.06）
 * @param healRate     盈余日回补系数（/日，默认 0.06）
 * @param horizon      模拟期程（天，默认 90）
 * @param kLow         低温流失敏感系数 K_LOW（默认 0.02）
 * @param kNov         新鲜疲劳流失系数 K_NOV（默认 0.004）
 * @param fatigueDay   新鲜疲劳起始天（默认 30，此天之后无解锁开始扣减）
 */
public record NumericConfig(
        double floor,
        double c,
        double capFree,
        double basicGrant,
        double premiumGrant,
        double kDecay,
        double healRate,
        int horizon,
        double kLow,
        double kNov,
        int fatigueDay
) {

    /** 温度上限，恒为 100（分档 60–75 / 75–90 / 90–100）。 */
    public static final double CEILING = 100.0;

    /**
     * §3.12 v1-tentative 暂定基线（2026-07-10）。
     *
     * <p>floor60 / c20 / capFree20 / basicGrant20 / premiumGrant80 / kDecay0.06 / healRate0.06，
     * horizon90 / kLow0.02 / kNov0.004 / fatigueDay30。</p>
     */
    public static NumericConfig defaultTentative() {
        return new NumericConfig(
                60.0,   // floor
                20.0,   // c
                20.0,   // capFree
                20.0,   // basicGrant
                80.0,   // premiumGrant
                0.06,   // kDecay
                0.06,   // healRate
                90,     // horizon
                0.02,   // kLow
                0.004,  // kNov
                30      // fatigueDay
        );
    }

    /** 温度演化跨度 span = 100 − floor（用于满意度/低温 hazard 归一化）。 */
    public double span() {
        return CEILING - floor;
    }

    /** 白嫖单宠可维持宠物数 = ⌊capFree / c⌋（§3.12：默认 20/20 = 1）。 */
    public int freeSustainablePets() {
        return (int) Math.floor(capFree / c);
    }
}
