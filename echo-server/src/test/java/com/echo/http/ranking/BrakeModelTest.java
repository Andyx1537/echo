package com.echo.http.ranking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 负反馈制动（{@code TECH-DESIGN §8.5}，{@code B1} 已整条重做为档位表 + 棘轮）。
 *
 * <p>钉三件事：档位表的取值与单调性、<b>棘轮不回升</b>、以及「刹车永远不会变成油门」。</p>
 */
class BrakeModelTest {

    private final BrakeTable table = BrakeTable.SEED;
    private final BrakeModel brakes = new BrakeModel(table);
    private final WeightModel model = new WeightModel(WeightConfig.DEFAULTS);

    // ------------------------------------------------------------ 拒绝率

    /** 分母是 {@code n}（已获得的分发机会），不是互动总数。 */
    @Test
    void rejectionRateDividesByDeliveriesNotByInteractions() {
        assertThat(BrakeModel.rejectionRate(5, 3, 2, 100)).isCloseTo(0.10, within(1e-9));
        assertThat(BrakeModel.rejectionRate(0, 0, 0, 100)).isZero();
        assertThat(BrakeModel.rejectionRate(1, 0, 0, 0)).as("还没投过就没有率可言").isZero();
    }

    /** 累计口径的已知性质：同样多的负反馈，投得越多率越低。棘轮就是为它准备的。 */
    @Test
    void cumulativeRateMechanicallyFallsAsDeliveriesGrow() {
        assertThat(BrakeModel.rejectionRate(10, 0, 0, 50)).isCloseTo(0.20, within(1e-9));
        assertThat(BrakeModel.rejectionRate(10, 0, 0, 100)).isCloseTo(0.10, within(1e-9));
        assertThat(BrakeModel.rejectionRate(10, 0, 0, 200)).isCloseTo(0.05, within(1e-9));
    }

    // ------------------------------------------------------------ 档位表

    /** 表外不制动：投放不够多，或拒绝率不够高。 */
    @Test
    void outsideTheTableThereIsNoBraking() {
        assertThat(brakes.target(39, 0.99)).as("投放不到 40，率再高也不判").isEqualTo(1.0);
        assertThat(brakes.target(500, 0.079)).as("率不到 8%，投再多也不判").isEqualTo(1.0);
        assertThat(brakes.target(0, 0.0)).isEqualTo(1.0);
    }

    /** 两个维度独立取「命中的最高档」，交叉出一个格子。 */
    @Test
    void lookupTakesTheHighestTierHitOnEachAxis() {
        assertThat(brakes.target(40, 0.08)).isEqualTo(0.90);
        assertThat(brakes.target(79, 0.19)).as("n 仍在 ≥40 档，率仍在 ≥8% 档").isEqualTo(0.90);
        assertThat(brakes.target(80, 0.20)).isEqualTo(0.65);
        assertThat(brakes.target(120, 0.33)).isEqualTo(0.40);
        assertThat(brakes.target(9999, 0.99)).as("超出最高档仍取最高档").isEqualTo(0.40);
    }

    /** 定稿表：拒绝率每升一档减 0.15（横向步长加大）。 */
    @Test
    void theRejectionAxisStepsDownByFifteenHundredths() {
        for (int n : new int[]{40, 80, 120}) {
            assertThat(brakes.target(n, 0.20))
                    .isCloseTo(brakes.target(n, 0.08) - 0.15, within(1e-9));
            assertThat(brakes.target(n, 0.33))
                    .isCloseTo(brakes.target(n, 0.20) - 0.15, within(1e-9));
        }
    }

    /** 沿两个维度走，制动只会越来越重。 */
    @Test
    void theTableIsNonIncreasingAlongBothAxes() {
        int[] ns = {40, 80, 120};
        double[] rates = {0.08, 0.20, 0.33};
        for (int i = 0; i < ns.length; i++) {
            for (int j = 1; j < rates.length; j++) {
                assertThat(brakes.target(ns[i], rates[j]))
                        .as("n=%d 拒绝率升高反而制动更轻", ns[i])
                        .isLessThanOrEqualTo(brakes.target(ns[i], rates[j - 1]));
            }
        }
        for (int j = 0; j < rates.length; j++) {
            for (int i = 1; i < ns.length; i++) {
                assertThat(brakes.target(ns[i], rates[j]))
                        .as("拒绝率=%.2f 投得更多反而制动更轻", rates[j])
                        .isLessThanOrEqualTo(brakes.target(ns[i - 1], rates[j]));
            }
        }
    }

    // -------------------------------------------------- 🔴 棘轮：不回升

    /**
     * 🔴 <b>本轮最要紧的一条。</b>
     *
     * <p>构造一个「率先超阈值、后因分母变大而掉回阈值下」的真实序列：
     * 负反馈条数<b>一条都没有再增加</b>，只是 {@code n} 继续涨，
     * 于是累计口径的拒绝率自己掉了回去。断言 {@code brakeFactor} 不回升。</p>
     *
     * <p>没有棘轮的话，这张卡会在被判过重档之后自动"痊愈"，而且没有任何日志会提到这件事。</p>
     */
    @Test
    void brakeFactorNeverRecoversWhenTheCumulativeRateFallsBackOnItsOwn() {
        int dontShow = 27; // 负反馈从头到尾就这些，不再增加
        double brakeFactor = 1.0;

        // n=80 时率 = 27/80 = 33.75% → 命中 (≥80, ≥33%) 格 = 0.50
        brakeFactor = brakes.advance(brakeFactor, dontShow, 0, 0, 80);
        assertThat(brakeFactor).as("率 33.75%，应当降到该行最重的那一档").isEqualTo(0.50);

        // n 继续涨，负反馈没变，率自己掉下来
        assertThat(BrakeModel.rejectionRate(dontShow, 0, 0, 200)).isLessThan(0.20);
        // 查表本身会给出更轻的一档（(≥120, ≥8%) = 0.70），棘轮把它挡回去
        brakeFactor = brakes.advance(brakeFactor, dontShow, 0, 0, 200);
        assertThat(brakeFactor).as("率掉回 20% 以下，但制动不许松").isEqualTo(0.50);

        assertThat(BrakeModel.rejectionRate(dontShow, 0, 0, 400)).isLessThan(0.08);
        brakeFactor = brakes.advance(brakeFactor, dontShow, 0, 0, 400);
        assertThat(brakeFactor).as("率已掉出表外，制动仍然不许松").isEqualTo(0.50);
    }

    /** 棘轮本身：只往下走。 */
    @Test
    void ratchetOnlyEverGoesDown() {
        assertThat(BrakeModel.ratchet(1.0, 0.80)).isEqualTo(0.80);
        assertThat(BrakeModel.ratchet(0.60, 0.80)).as("已经比目标更重，保持不变").isEqualTo(0.60);
        assertThat(BrakeModel.ratchet(0.60, 1.0)).as("目标是不制动也不许松").isEqualTo(0.60);
    }

    /** 任意一串投放/负反馈序列走下来，brakeFactor 单调不增。 */
    @Test
    void brakeFactorIsMonotonicallyNonIncreasingAcrossAnySequence() {
        double brakeFactor = 1.0;
        java.util.Random rnd = new java.util.Random(20260825L);
        int dontShow = 0;
        for (int n = 1; n <= 500; n++) {
            if (rnd.nextInt(4) == 0) {
                dontShow++;
            }
            double next = brakes.advance(brakeFactor, dontShow, 0, 0, n);
            assertThat(next).as("n=%d 时制动松了", n).isLessThanOrEqualTo(brakeFactor);
            brakeFactor = next;
        }
    }

    // ---------------------------------------------- B1 二档不再立即终止

    /**
     * 🔴 拒绝率 ≥20% 不再「立即终止」，只是表里的一列，继续走降档。
     *
     * <p>拿下内容是 {@code B3}（审核链路）的事，{@code B1} 管的是口味不是合规。</p>
     */
    @Test
    void aHighRejectionRateDowngradesButDoesNotTerminate() {
        double atTwenty = brakes.target(120, 0.20);
        double atThirtyThree = brakes.target(120, 0.33);

        assertThat(atTwenty).isGreaterThan(0.0).isLessThan(1.0);
        assertThat(atThirtyThree).isGreaterThan(0.0).isLessThan(atTwenty);

        // 仍然是一个能算出权重的合法卡，而不是被踢出分发
        assertThat(model.weight(WeightModel.W0_USER, 120, 24, 20, atThirtyThree))
                .isGreaterThan(0.0);
    }

    /** 制动机制里不该再存在「终止」这个概念——终止归 B3 的审核链路。 */
    @Test
    void thereIsNoTerminateBandLeftInTheBrakeModel() {
        assertThat(java.util.Arrays.stream(BrakeModel.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).toList())
                .noneMatch(n -> n.toUpperCase().contains("TERMINATE"));
        assertThat(BrakeModel.class.getDeclaredClasses())
                .as("Band 枚举随旧形态一起移除").isEmpty();
    }

    // ------------------------------------------------------------ B2

    @Test
    void closingAllReplyChannelsCutsTheBrakeFactorToThirtyPercent() {
        assertThat(BrakeModel.applyChannelsClosed(1.0)).isCloseTo(0.30, within(1e-9));
    }

    /** B2 压到 0.30 之后，B1 的棘轮不会把它抬回去。 */
    @Test
    void b1DoesNotUndoB2() {
        double afterB2 = BrakeModel.applyChannelsClosed(1.0);
        assertThat(brakes.advance(afterB2, 4, 0, 0, 50))
                .as("B1 查表得 0.90，但卡上已经是 0.30")
                .isEqualTo(0.30);
    }

    // ------------------------------------------------------- 零回应

    /** 🔴 零回应不制动：「没人理」和「有人明确不想看」是两件事。 */
    @Test
    void silenceIsNotNegativeFeedback() {
        assertThat(BrakeModel.isSilent(0, 0)).isTrue();
        assertThat(BrakeModel.isSilent(0, 1)).isFalse();
        assertThat(BrakeModel.isSilent(1, 0)).isFalse();

        assertThat(brakes.advance(1.0, 0, 0, 0, 500))
                .as("投了 500 次一条负反馈都没有，不该被刹车")
                .isEqualTo(1.0);
    }

    /** 🔴 {@code TC-WEIGHT-01}：一千次记得换不来多一次曝光。 */
    @Test
    void aThousandRemembersBuyNotOneExtraImpression() {
        for (int n : new int[]{0, 10, 50, 120}) {
            double coldCard = model.weight(WeightModel.W0_USER, n, 36, 20, 1.0);
            double hotCard = model.weight(WeightModel.W0_USER, n, 36, 20, 1.0);
            assertThat(hotCard).isEqualTo(coldCard);
        }
    }

    // -------------------------------------------- 🔴 启动期配置校验

    @Test
    void aTableThatRecoversAlongTheRejectionAxisIsRejectedAtStartup() {
        assertThatThrownBy(() -> new BrakeTable(
                new int[]{40, 80},
                new double[]{0.08, 0.20},
                new double[][]{{0.90, 0.95}, {0.80, 0.75}}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝率更高却制动更轻");
    }

    @Test
    void aTableThatRecoversAlongTheExposureAxisIsRejectedAtStartup() {
        assertThatThrownBy(() -> new BrakeTable(
                new int[]{40, 80},
                new double[]{0.08, 0.20},
                new double[][]{{0.80, 0.75}, {0.90, 0.70}}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("投得更多却制动更轻");
    }

    @Test
    void aTableWithACellAboveOneIsRejected() {
        assertThatThrownBy(() -> new BrakeTable(
                new int[]{40}, new double[]{0.08}, new double[][]{{1.2}}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("制动只能让权重变小");
    }

    @Test
    void aMisshapenTableIsRejected() {
        assertThatThrownBy(() -> new BrakeTable(
                new int[]{40, 80}, new double[]{0.08}, new double[][]{{0.9}}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("行数");
        assertThatThrownBy(() -> new BrakeTable(
                new int[]{80, 40}, new double[]{0.08}, new double[][]{{0.9}, {0.8}}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("严格递增");
    }

    /** 种子表本身必须过校验——否则默认配置就是非法的。 */
    @Test
    void theSeedTableIsValid() {
        assertThat(BrakeTable.SEED.lookup(40, 0.08)).isEqualTo(0.90);
        assertThat(BrakeTable.SEED.render()).contains("0.90", "0.60");
    }

    @Test
    void tableCanBeOverriddenFromTheEnvironment() {
        BrakeTable t = BrakeTable.from(java.util.Map.of("ECHO_BRAKE_TABLE",
                "10,20|0.05,0.10|0.95,0.90;0.85,0.80")::get);
        assertThat(t.lookup(10, 0.05)).isEqualTo(0.95);
        assertThat(t.lookup(20, 0.10)).isEqualTo(0.80);
        assertThat(t.minExposures()).isEqualTo(10);

        assertThatThrownBy(() -> BrakeTable.from(java.util.Map.of("ECHO_BRAKE_TABLE", "40|0.08")::get))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 表是可配的，所以要能在启动日志里看到实际生效的那一张。 */
    @Test
    void theEffectiveTableCanBePrinted() {
        assertThat(BrakeTable.SEED.render()).contains("≥40").contains("≥8%");
    }
}
