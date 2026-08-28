package com.echo.http.ranking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 制动效果度量：被刹车与未被刹车的卡，平均还能被投多少次。
 *
 * <p>这里除了验度量本身，还把「当前种子数值效果很小」这个已知事实<b>算出来钉住</b>——
 * 它是制作人做判断的依据，不能只写在文档里。</p>
 */
class BrakeMetricsTest {

    private final WeightModel model = new WeightModel(WeightConfig.DEFAULTS);

    /** 未被刹车的卡在纯 γⁿ 曲线上活到第 99 次投放。 */
    @Test
    void anUnbrakedCardSurvivesAboutNinetyNineDeliveries() {
        // 龄期 0 → T(a)=1，把时间衰减摘掉，单看投放衰减
        assertThat(model.remainingDeliveries(WeightModel.W0_USER, 0, 0, 20, 1.0))
                .isEqualTo(99);
    }

    /**
     * 🔴 <b>刹车到底值多少：约 7 次投放。</b>
     *
     * <p>制作人给的估算是 98 → 93（差 5 次）。实算是 <b>99 → 92（差 7 次）</b>。
     * 结论方向不变，但差值比估算大一点，已在 {@code docs/BRAKE-CALIBRATION.md} 更正。</p>
     */
    @Test
    void theSeedBrakeIsWorthAboutSevenDeliveries() {
        int free = model.remainingDeliveries(WeightModel.W0_USER, 0, 0, 20, 1.0);
        int braked = model.remainingDeliveries(WeightModel.W0_USER, 0, 0, 20, 0.80);

        assertThat(free).isEqualTo(99);
        assertThat(braked).isEqualTo(92);
        assertThat(free - braked)
                .as("这 7 次就是 0.80 那一档刹车的全部效果")
                .isEqualTo(7);
    }

    /**
     * 闭式：制动 {@code b} 恰好值 {@code ln(1/b)/ln(1/γ)} 次投放。
     *
     * <p>连最重的一档（0.60）也只值约 17 次，占 99 次的六分之一。</p>
     */
    @Test
    void theCostOfABrakeMatchesTheClosedForm() {
        double lnGamma = -Math.log(WeightConfig.DEFAULTS.gamma());
        for (double b : new double[]{0.90, 0.80, 0.70, 0.60}) {
            int free = model.remainingDeliveries(WeightModel.W0_USER, 0, 0, 20, 1.0);
            int braked = model.remainingDeliveries(WeightModel.W0_USER, 0, 0, 20, b);
            assertThat((double) (free - braked))
                    .as("b=%.2f", b)
                    .isCloseTo(Math.log(1 / b) / lnGamma, within(1.0));
        }
    }

    /** 越重的刹车剩得越少，单调。 */
    @Test
    void heavierBrakesLeaveFewerDeliveries() {
        int prev = Integer.MAX_VALUE;
        for (double b : new double[]{1.0, 0.90, 0.85, 0.80, 0.75, 0.70, 0.65, 0.60}) {
            int remaining = model.remainingDeliveries(WeightModel.W0_USER, 0, 0, 20, b);
            assertThat(remaining).isLessThanOrEqualTo(prev);
            prev = remaining;
        }
    }

    /** 已经跌破退场线的卡剩 0 次。 */
    @Test
    void aCardAlreadyBelowTheExitLineHasNothingLeft() {
        assertThat(model.remainingDeliveries(WeightModel.W0_USER, 0, 400, 0, 1.0)).isZero();
    }

    // -------------------------------------------------------- 度量聚合

    @Test
    void metricsSeparateBrakedFromUnbrakedCards() {
        BrakeMetrics metrics = new BrakeMetrics();
        metrics.observe(1.0, 99);
        metrics.observe(1.0, 97);
        metrics.observe(0.80, 92);
        metrics.observe(0.70, 88);

        BrakeMetrics.Snapshot s = metrics.snapshot();
        assertThat(s.unbrakedCards()).isEqualTo(2);
        assertThat(s.brakedCards()).isEqualTo(2);
        assertThat(s.unbrakedAvgRemaining()).isCloseTo(98.0, within(1e-9));
        assertThat(s.brakedAvgRemaining()).isCloseTo(90.0, within(1e-9));
        assertThat(s.gap()).isCloseTo(8.0, within(1e-9));
        assertThat(s.relativeGap()).isCloseTo(8.0 / 98.0, within(1e-9));
    }

    /**
     * 🔴 两个数字贴在一起时，指标要能<b>看出来</b>刹车是装饰。
     *
     * <p>这条用例的意义不是断言某个数，是确认这个度量<b>报得出</b>「几乎没差别」这件事。
     * 报不出来的话，看板上会只剩「有多少卡被刹了车」那个好看但没用的数。</p>
     */
    @Test
    void aDecorativeBrakeShowsUpAsANearZeroGap() {
        BrakeMetrics metrics = new BrakeMetrics();
        metrics.observe(1.0, 99);
        metrics.observe(0.99, 99);

        assertThat(metrics.snapshot().gap()).isZero();
        assertThat(metrics.snapshot().toString()).contains("差 0.0 次");
    }

    /** 一侧无样本时不要报出一个看起来正常的差值。 */
    @Test
    void gapIsNotANumberWhenOneSideHasNoSamples() {
        BrakeMetrics metrics = new BrakeMetrics();
        metrics.observe(0.8, 90);
        assertThat(metrics.snapshot().gap()).isNaN();
    }
}
