package com.echo.harness;

/**
 * 单画像模拟结果（EXP-BOTS §3 模拟循环产出 · §4 分群反馈指标）。
 *
 * @param persona  被模拟的画像
 * @param survival 逐日留存曲线 S[0..horizon]，S[0]=1.0（起始）
 * @param temp     逐日温度曲线 T[0..horizon]，T[0]=100（起始）
 * @param avgTemp  期程内平均温度（含起始日）
 * @param d1       D1 留存（第 1 天末生存率）
 * @param d7       D7 留存
 * @param d30      D30 留存
 * @param d60      D60 留存
 * @param d90      D90 留存
 * @param ltv      90 天 LTV ≈ 月费 × (Σ S_d)/30
 */
public record SegmentResult(
        BotPersona persona,
        double[] survival,
        double[] temp,
        double avgTemp,
        double d1,
        double d7,
        double d30,
        double d60,
        double d90,
        double ltv
) {

    /** 期程末（D90 等价）温度。 */
    public double finalTemp() {
        return temp[temp.length - 1];
    }
}
