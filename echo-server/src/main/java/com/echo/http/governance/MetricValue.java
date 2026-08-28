package com.echo.http.governance;

/**
 * 一个指标值，🔴 <b>区分「源关着」与「源开着但没人做」</b>（{@code DECISIONS §G⁗⁗⁗‴ S13 ③}）。
 *
 * <p>裁定原文针对的是深共鸣率：{@code R3}/{@code R4} 是它唯一的数据源，留一句话开关关闭期间
 * 若返回 0，会被读成「<b>没人深度互动</b>」——那是错误结论，而且是会驱动错误动作的错误结论
 * （有人会去改产品、改推荐，而真实原因只是那个功能还没开）。</p>
 *
 * <p>本类把这条做成<b>通用口径</b>而不是只修深共鸣率一处：任何「数据源可能整体不可用」的指标
 * 都应当用它来承载，因为同一个陷阱在每一个这类指标上都成立。三种状态必须能分开：</p>
 *
 * <table>
 *   <tr><th>状态</th><th>含义</th><th>出参</th></tr>
 *   <tr><td>{@link #sourceOff}</td><td>数据源整体关闭，这个数<b>不存在</b></td><td>{@code null} + 原因</td></tr>
 *   <tr><td>{@link #noSample}</td><td>源开着，但分母为 0（还没有样本）</td><td>{@code null} + 原因</td></tr>
 *   <tr><td>{@link #of}</td><td>有真实测量值（含真实的 0）</td><td>数值</td></tr>
 * </table>
 *
 * <p>🔴 展示层<b>不得</b>把 {@link #value()} 的 null 兜底成 0。所以这里刻意不提供
 * {@code valueOrZero()} 之类的便捷方法——那个方法一旦存在，就一定会有人调用它。</p>
 */
public record MetricValue(Double value, State state, String reason) {

    public enum State {
        /** 🔴 数据源关闭，这个指标当期<b>无数据</b>，不是 0。 */
        SOURCE_OFF,
        /** 源正常，但还没有样本（分母 0）。同样是「无数据」，但原因不同，运营的动作也不同。 */
        NO_SAMPLE,
        /** 有真实测量值。此时 0 是真的 0（源开着、有样本、就是没人做）。 */
        MEASURED
    }

    /** 数据源整体关闭。 */
    public static MetricValue sourceOff(String reason) {
        return new MetricValue(null, State.SOURCE_OFF, reason);
    }

    /** 源开着但分母为 0。 */
    public static MetricValue noSample(String reason) {
        return new MetricValue(null, State.NO_SAMPLE, reason);
    }

    /** 真实测量值（可以是 0，那个 0 是有意义的）。 */
    public static MetricValue of(double value) {
        return new MetricValue(value, State.MEASURED, null);
    }

    /** 是否有可展示的数值。false 时展示层必须显示「无数据」而不是 0。 */
    public boolean hasValue() {
        return state == State.MEASURED;
    }
}
