package com.echo.harness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 体验 Bot 内循环 CI 收敛护栏（EXP-BOTS §8.3）。
 *
 * <p>以 §3.12 v1-tentative 基线跑内置画像库，断言四条收敛判据；数值一旦被改到伤害红线群体或
 * 大盘塌陷，本测试变红——这就是内循环的护栏。</p>
 */
class ExpBotsHarnessTest {

    private NumericConfig cfg;
    private Population population;
    private AggregateReport report;
    private Map<String, SegmentResult> byId;

    @BeforeEach
    void setUp() {
        cfg = NumericConfig.defaultTentative();
        population = Population.loadDefault();
        report = ExpBotsHarness.run(cfg, population);
        byId = report.segments().stream()
                .collect(Collectors.toMap(s -> s.persona().id(), s -> s));
    }

    @Test
    void enabledPopulationWeightsNormalizeToOne() {
        List<BotPersona> enabled = population.enabledNormalized();
        assertThat(enabled).hasSize(6);
        double sum = enabled.stream().mapToDouble(BotPersona::weight).sum();
        assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    /** 判据 1（红线）：所有 grief 画像 avgTemp ≥ lowTemp 且 d30 ≥ 0.5（安全第一）。 */
    @Test
    void griefSegmentsStayAboveLowTempAndRetain() {
        List<SegmentResult> grief = report.segments().stream()
                .filter(s -> s.persona().grief())
                .toList();
        assertThat(grief).isNotEmpty();
        for (SegmentResult s : grief) {
            assertThat(s.avgTemp())
                    .as("grief[%s] avgTemp≥lowTemp", s.persona().id())
                    .isGreaterThanOrEqualTo(s.persona().lowTemp());
            assertThat(s.d30())
                    .as("grief[%s] d30≥0.5", s.persona().id())
                    .isGreaterThanOrEqualTo(0.5);
        }
        assertThat(report.redlineRiskGroups()).isZero();
    }

    /** 判据 2：白嫖单宠可稳 —— ⌊capFree/c⌋ ≥ 1。 */
    @Test
    void freeSinglePetIsSustainable() {
        assertThat(cfg.freeSustainablePets()).isGreaterThanOrEqualTo(1);
    }

    /** 判据 3：付费群有增长感 —— premium 画像 avgTemp &lt; 99（非秒满）且高于白嫖同类。 */
    @Test
    void premiumHasGrowthHeadroomAndBeatsFree() {
        List<SegmentResult> premium = report.segments().stream()
                .filter(s -> s.persona().tier() == Tier.PREMIUM)
                .toList();
        assertThat(premium).isNotEmpty();

        double maxFreeAvgTemp = report.segments().stream()
                .filter(s -> s.persona().tier() == Tier.NONE)
                .mapToDouble(SegmentResult::avgTemp)
                .max()
                .orElse(0.0);

        for (SegmentResult s : premium) {
            assertThat(s.avgTemp())
                    .as("premium[%s] avgTemp<99（非秒满）", s.persona().id())
                    .isLessThan(99.0);
            assertThat(s.avgTemp())
                    .as("premium[%s] 高于白嫖画像温度", s.persona().id())
                    .isGreaterThan(maxFreeAvgTemp);
        }
    }

    /** 判据 4：大盘加权 D30 ≥ 目标带下限（先设 0.30 占位）。 */
    @Test
    void aggregateD30MeetsFloor() {
        assertThat(report.weightedD30()).isGreaterThanOrEqualTo(0.30);
    }

    @Test
    void temperatureModelHonoursFloorAndMaintenanceInvariant() {
        // 恰好维系（heartsPerPet == c）→ 温度不变
        assertThat(TemperatureModel.step(85.0, cfg.c(), cfg)).isEqualTo(85.0);
        // 欠维系 → 朝地板回落，且永不破地板
        double decayed = TemperatureModel.step(cfg.floor() + 5.0, 0.0, cfg);
        assertThat(decayed).isLessThan(cfg.floor() + 5.0).isGreaterThanOrEqualTo(cfg.floor());
        // clamp：低于地板的输入被拉回地板
        assertThat(TemperatureModel.step(10.0, 0.0, cfg)).isEqualTo(cfg.floor());
    }
}
