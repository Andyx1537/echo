package com.echo.http.governance;

/**
 * 深共鸣率（{@code SPEC-admin-console §2.1.1 ⑤} / {@code §2.5}）。
 *
 * <p>口径：{@code R3 留一句话} 与 {@code R4 我也想起一件事} 占全部有效回声的比例。</p>
 *
 * <p>🔴 {@code S13 ③}：这两个类型<b>都是自由文本</b>，都受留一句话开关门控。开关关闭期间
 * 它们不可能产生数据，此时指标必须是<b>无数据</b>而不是 0 —— 见 {@link MetricValue}。</p>
 */
public final class DeepResonanceRate {

    /** 计入深共鸣的回声类型（🔴 与 {@code t_resonance_type} 的 code 对齐）。 */
    public static final String TYPE_LEAVE_WORDS = "R3";
    public static final String TYPE_ME_TOO = "R4";

    private final FeatureSwitchService switches;

    public DeepResonanceRate(FeatureSwitchService switches) {
        this.switches = switches;
    }

    /**
     * 计算深共鸣率。
     *
     * @param deepCount  R3 + R4 的有效回声数
     * @param totalCount 全部有效回声数（分母）
     */
    public MetricValue compute(long deepCount, long totalCount) {
        // 🔴 先判源：开关关着时，分子必然是 0，但那个 0 不表示"没人深度互动"，
        //    而表示"没有渠道可以深度互动"。这两件事的运营动作完全相反。
        if (!switches.isLeaveWordsEnabled()) {
            return MetricValue.sourceOff("留一句话开关关闭，R3/R4 无数据来源");
        }
        if (totalCount <= 0) {
            return MetricValue.noSample("当期没有有效回声");
        }
        return MetricValue.of((double) deepCount / (double) totalCount);
    }
}
