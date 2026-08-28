package com.echo.http.governance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code S13 ③} —— 🔴 开关关闭期间深共鸣率必须是<b>无数据</b>，不得是 0。
 *
 * <p>{@code R3}/{@code R4} 是它唯一的数据源。源被关掉时返回 0 会被读成「没人深度互动」——
 * 那是错误结论，而且是<b>会驱动错误动作</b>的错误结论：有人会去改产品、改推荐，
 * 而真实原因只是那个功能还没开。</p>
 *
 * <p>这里同时钉住「源关着」与「源开着但没人做」<b>必须可区分</b>——这是本轮要求做成的通用口径。</p>
 */
class DeepResonanceRateTest {

    private DeepResonanceRate rateWithSwitch(boolean enabled) {
        CapabilityRegistry caps = new CapabilityRegistry();
        FeatureSwitchStore store = new FeatureSwitchStore(null);
        if (enabled) {
            for (GovernanceCapability c : GovernanceCapability.values()) {
                caps.register(c, () -> true);
            }
            store.setEnabled(FeatureSwitchService.KEY_LEAVE_WORDS, true, 1L, 2L,
                    System.currentTimeMillis());
        }
        return new DeepResonanceRate(new FeatureSwitchService(store, caps));
    }

    /** 🔴 开关关闭 → SOURCE_OFF，value 为 null。 */
    @Test
    void returnsNullNotZeroWhenSwitchOff() {
        MetricValue v = rateWithSwitch(false).compute(0, 1000);
        assertThat(v.value()).as("🔴 不得返回 0").isNull();
        assertThat(v.state()).isEqualTo(MetricValue.State.SOURCE_OFF);
        assertThat(v.hasValue()).isFalse();
        assertThat(v.reason()).contains("留一句话开关关闭");
    }

    /**
     * 🔴 三种状态必须互相可区分。
     *
     * <p>「源关着」「源开着但没样本」「源开着有样本但没人做（真实的 0）」——
     * 前两者都不该显示数字，第三个的 0 是有意义的。若只用一个 nullable double 承载，
     * 前两者就分不开了。</p>
     */
    @Test
    void distinguishesSourceOffFromNoSampleFromRealZero() {
        MetricValue off = rateWithSwitch(false).compute(0, 0);
        MetricValue noSample = rateWithSwitch(true).compute(0, 0);
        MetricValue realZero = rateWithSwitch(true).compute(0, 500);

        assertThat(off.state()).isEqualTo(MetricValue.State.SOURCE_OFF);
        assertThat(noSample.state()).isEqualTo(MetricValue.State.NO_SAMPLE);
        assertThat(realZero.state()).isEqualTo(MetricValue.State.MEASURED);

        assertThat(off.hasValue()).isFalse();
        assertThat(noSample.hasValue()).isFalse();
        assertThat(realZero.hasValue()).as("源开着、有样本、就是没人做 → 这个 0 是真的").isTrue();
        assertThat(realZero.value()).isZero();
    }

    /** 正常计算。 */
    @Test
    void computesRateWhenSourceOn() {
        MetricValue v = rateWithSwitch(true).compute(30, 200);
        assertThat(v.state()).isEqualTo(MetricValue.State.MEASURED);
        assertThat(v.value()).isEqualTo(0.15);
    }

    /** 🔴 MetricValue 刻意不提供 valueOrZero() 之类的便捷方法——存在即会被调用。 */
    @Test
    void offersNoZeroFallbackHelper() {
        assertThat(MetricValue.class.getMethods())
                .noneMatch(m -> m.getName().toLowerCase().contains("orzero")
                        || m.getName().toLowerCase().contains("ordefault"));
    }
}
