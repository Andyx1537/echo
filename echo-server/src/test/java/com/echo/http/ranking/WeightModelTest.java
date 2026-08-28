package com.echo.http.ranking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 权重衰减模型（{@code TECH-DESIGN §8.1–§8.5}）。
 *
 * <p>这套公式里没有一个常数是可以"差不多"的，所以规格里给出的每个锚点都在这里钉一遍。
 * 但比锚点更要紧的是最后那组<b>性质</b>用例：{@code W ≤ W0}、只减不增、
 * 高互动换不来多一次曝光——那才是模型存在的理由，锚点只是它的表现。</p>
 */
class WeightModelTest {

    private final WeightConfig cfg = WeightConfig.DEFAULTS;
    private final WeightModel model = new WeightModel(cfg);

    // -------------------------------------------------- 规格给出的锚点

    /** {@code §8.1.4}：72h → 0.368 · 144h → 0.050 · 168h → 0.026。 */
    @Test
    void timeDecayHitsTheAnchorsInTheSpec() {
        assertThat(model.timeDecay(0)).isEqualTo(1.0);
        assertThat(model.timeDecay(72)).isCloseTo(0.368, within(0.001));
        assertThat(model.timeDecay(144)).isCloseTo(0.050, within(0.001));
        assertThat(model.timeDecay(168)).isCloseTo(0.026, within(0.001));
    }

    /** 过了 72h 换成快衰段，衰减速度正好翻倍（τ 由 72 变 36）。 */
    @Test
    void timeDecayIsContinuousAtTheSegmentBoundaryAndFallsFasterAfterIt() {
        double atBoundary = model.timeDecay(72);
        assertThat(model.timeDecay(72.001)).isCloseTo(atBoundary, within(1e-4));

        double slowSegmentDropPerHour = model.timeDecay(71) - model.timeDecay(72);
        double fastSegmentDropPerHour = model.timeDecay(72) - model.timeDecay(73);
        assertThat(fastSegmentDropPerHour).isGreaterThan(slowSegmentDropPerHour);
    }

    /** {@code §8.3}：γ=0.97 的半衰期约 23 次；100 次后 0.048；300 次后 1.1e-4。 */
    @Test
    void gammaHalfLifeIsAboutTwentyThreeDeliveries() {
        assertThat(Math.pow(cfg.gamma(), 23)).isCloseTo(0.50, within(0.005));
        assertThat(Math.pow(cfg.gamma(), 100)).isCloseTo(0.048, within(0.001));
        assertThat(Math.pow(cfg.gamma(), 300)).isCloseTo(1.1e-4, within(1e-5));
    }

    /** {@code §8.4.3}：{@code D_min = clamp(P25, 20, 100)}。 */
    @Test
    void dMinIsClampedBetweenTwentyAndOneHundred() {
        assertThat(model.dMin(0)).isEqualTo(20);
        assertThat(model.dMin(19)).isEqualTo(20);
        assertThat(model.dMin(55)).isEqualTo(55);
        assertThat(model.dMin(100)).isEqualTo(100);
        assertThat(model.dMin(4000)).isEqualTo(100);
    }

    /** {@code §8.3}：{@code W_MIN} 按 {@code W0} 的比例算，官方内容的退场线也随之更低。 */
    @Test
    void minWeightScalesWithW0() {
        assertThat(model.minWeight(WeightModel.W0_USER)).isCloseTo(0.05, within(1e-9));
        assertThat(model.minWeight(WeightModel.W0_OFFICIAL)).isCloseTo(0.015, within(1e-9));
    }

    // ----------------------------------------------------- 欠投地板

    /** 欠投时地板托住，投够了地板撤走。 */
    @Test
    void fairnessFloorHoldsUpUnderDeliveredCardsAndDisappearsOnceTheyCatchUp() {
        double oldAge = 168; // T(a) 已经掉到 0.026，远低于 0.15 的地板
        assertThat(model.effectiveTimeDecay(oldAge, 5, 20))
                .as("还没投够，地板托住").isCloseTo(cfg.fairFloor(), within(1e-9));
        assertThat(model.effectiveTimeDecay(oldAge, 20, 20))
                .as("投够了，回到真实的时间衰减").isCloseTo(model.timeDecay(oldAge), within(1e-9));
    }

    /** 地板只托底，不抬高：内容还新的时候 T(a) 本来就高于地板，地板不起作用。 */
    @Test
    void theFloorNeverRaisesAFreshCardAboveItsNaturalDecay() {
        assertThat(model.effectiveTimeDecay(1, 0, 100))
                .isCloseTo(model.timeDecay(1), within(1e-9));
    }

    // ------------------------------------------------ 🔴 模型的核心性质

    /** 🔴 {@code W} 永远不超过 {@code W0}——没有任何回升路径。 */
    @Test
    void weightNeverExceedsW0() {
        for (int n = 0; n <= 200; n += 7) {
            for (double age : new double[]{0, 1, 24, 72, 100, 168, 400}) {
                double w = model.weight(WeightModel.W0_USER, n, age, model.dMin(30), 1.0);
                assertThat(w)
                        .as("n=%d age=%.0f 时 W 超过了 W0，模型的核心性质失效", n, age)
                        .isLessThanOrEqualTo(WeightModel.W0_USER);
            }
        }
    }

    /** 🔴 投得越多权重越低，单调不增。 */
    @Test
    void weightIsMonotonicallyNonIncreasingInDeliveries() {
        double prev = Double.MAX_VALUE;
        for (int n = 0; n <= 150; n++) {
            double w = model.weight(WeightModel.W0_USER, n, 100, model.dMin(0), 1.0);
            assertThat(w).as("n=%d 时权重反而涨了", n).isLessThanOrEqualTo(prev);
            prev = w;
        }
    }

    /** 🔴 时间越久权重越低，单调不增。 */
    @Test
    void weightIsMonotonicallyNonIncreasingInAge() {
        double prev = Double.MAX_VALUE;
        for (int age = 0; age <= 400; age += 4) {
            double w = model.weight(WeightModel.W0_USER, 200, age, model.dMin(0), 1.0);
            assertThat(w).as("age=%d 时权重反而涨了", age).isLessThanOrEqualTo(prev);
            prev = w;
        }
    }

    /**
     * 🔴 <b>互动量是刹车不是油门</b>：模型里根本没有接受互动量的入口。
     *
     * <p>{@code weight(...)} 的形参只有 {@code w0/n/age/dMin/gamma/brakeFactor}。
     * 想让「很多人喜欢」抬高权重，必须先改签名——这条用例盯的就是那次改签名。</p>
     */
    @Test
    void theWeightFunctionHasNowhereToPutAnInteractionCount() {
        // 🔴 盯签名而不是参数名：本工程没开 -parameters，反射拿到的名字是 arg0/arg1，
        //    按名字做正则匹配会永远为真，是一条假绿的用例
        java.util.Set<java.util.List<Class<?>>> signatures = java.util.Arrays.stream(
                        WeightModel.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("weight"))
                .map(m -> java.util.List.<Class<?>>of(m.getParameterTypes()))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(signatures)
                .as("weight(...) 多出一个入参，就该有人回答那个入参是不是互动量")
                .containsExactlyInAnyOrder(
                        java.util.List.of(double.class, int.class, double.class, int.class,
                                double.class, double.class),
                        java.util.List.of(double.class, int.class, double.class, int.class,
                                double.class));
    }

    /** 🔴 制动系数大于 1 就是把刹车踩成油门，值本身就不合法。 */
    @Test
    void brakeFactorAboveOneIsRejected() {
        assertThatThrownBy(() -> model.weight(WeightModel.W0_USER, 1, 1, 20, 1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("制动只能让权重变小");
        assertThatThrownBy(() -> model.weight(WeightModel.W0_USER, 1, 1, 20, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 官方内容起步就比用户内容低——官方号天然有分发优势，用 W0 抵掉。 */
    @Test
    void officialContentStartsLowerThanUserContent() {
        assertThat(WeightModel.W0_OFFICIAL).isLessThan(WeightModel.W0_USER);
        assertThat(model.weight(WeightModel.W0_OFFICIAL, 0, 0, 20, 1.0))
                .isLessThan(model.weight(WeightModel.W0_USER, 0, 0, 20, 1.0));
    }

    // ---------------------------------------------------------- 配置校验

    /** 🔴 {@code MIN_RATIO} 必须小于 {@code FAIR_FLOOR}，否则扶持与退场互相打架。 */
    @Test
    void configRefusesToStartWhenTheFloorSitsBelowTheExitLine() {
        assertThatThrownBy(() -> new WeightConfig(0.97, 72, 36, 72, 0.05, 0.15, 20, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须小于");
    }

    @Test
    void configRejectsGammaOutsideTheOpenUnitInterval() {
        assertThatThrownBy(() -> new WeightConfig(1.0, 72, 36, 72, 0.15, 0.05, 20, 100))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new WeightConfig(0.0, 72, 36, 72, 0.15, 0.05, 20, 100))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 配错了就起不来：回落到默认值等于「配置写了但没生效」，几天后看数据才发现。 */
    @Test
    void configRefusesGarbageInsteadOfFallingBackToDefaults() {
        assertThatThrownBy(() -> WeightConfig.from(k -> "ECHO_WEIGHT_GAMMA".equals(k) ? "fast" : null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是数字");
        assertThatThrownBy(() -> WeightConfig.from(k -> "ECHO_WEIGHT_DMIN_CLAMP".equals(k) ? "20" : null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void configReadsEveryKnobFromTheEnvironment() {
        WeightConfig c = WeightConfig.from(java.util.Map.of(
                "ECHO_WEIGHT_GAMMA", "0.90",
                "ECHO_WEIGHT_TAU1_HOURS", "48",
                "ECHO_WEIGHT_DMIN_CLAMP", "10,50")::get);

        assertThat(c.gamma()).isEqualTo(0.90);
        assertThat(c.tau1Hours()).isEqualTo(48.0);
        assertThat(c.dMinLow()).isEqualTo(10);
        assertThat(c.dMinHigh()).isEqualTo(50);
        assertThat(c.fairFloor()).as("没配的走默认").isEqualTo(0.15);
    }
}
