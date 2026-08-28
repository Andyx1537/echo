package com.echo.harness;

import java.util.ArrayList;
import java.util.List;

/**
 * 期望值日循环模拟器（默认引擎，EXP-BOTS §3 模拟循环）。
 *
 * <p>对每个画像跑 {@code horizon} 天，起始温度 100，逐日演化温度并累乘生存率，产出 {@link SegmentResult}。</p>
 *
 * <p><b>温度演化的期望值处理</b>：期望日暖意 = {@code pOpen·(capFree+tierGrant)}（见 §3 步骤 1）。
 * 由于 {@link TemperatureModel#step} 对"当日投入"是非线性的（欠维系走衰减、盈余走回暖，分段），
 * 直接把 pOpen 折算进每宠暖意再单步演化，会让高档订阅"秒满贴顶"而失真。这里采用等价的期望值口径：
 * 当日温度 = pOpen · (打开日：白嫖+附赠拉满) + (1−pOpen) · (未打开日：零投入回落)，
 * 其期望每宠暖意仍等于 {@code pOpen·(capFree+tierGrant)/pets}，但更真实地体现"来了才暖、不来就落"，
 * 从而让付费群体呈现"高而非满"的可感知增长感（对齐 §5 收敛判据 3 与 §8.3 断言 3）。</p>
 */
public final class Simulator {

    /** 模拟一组画像。 */
    public List<SegmentResult> simulateAll(List<BotPersona> personas, NumericConfig cfg) {
        List<SegmentResult> out = new ArrayList<>(personas.size());
        for (BotPersona p : personas) {
            out.add(simulate(p, cfg));
        }
        return out;
    }

    /** 模拟单个画像。 */
    public SegmentResult simulate(BotPersona persona, NumericConfig cfg) {
        int horizon = cfg.horizon();
        double[] temp = new double[horizon + 1];
        double[] survival = new double[horizon + 1];

        temp[0] = NumericConfig.CEILING;
        survival[0] = 1.0;

        double openHeartsPerPet = persona.dailyWarmthCap(cfg) / Math.max(1, persona.pets());
        double tempSum = temp[0];

        for (int day = 1; day <= horizon; day++) {
            double prev = temp[day - 1];

            // 期望值温度：pOpen 打开日拉满投入 + (1−pOpen) 未打开日零投入
            double tOpen = TemperatureModel.step(prev, openHeartsPerPet, cfg);
            double tClosed = TemperatureModel.step(prev, 0.0, cfg);
            double t = persona.pOpen() * tOpen + (1.0 - persona.pOpen()) * tClosed;
            temp[day] = t;
            tempSum += t;

            double hazard = dailyHazard(persona, cfg, t, day);
            survival[day] = survival[day - 1] * (1.0 - hazard);
        }

        double avgTemp = tempSum / (horizon + 1);

        double survivalSum = 0.0;
        for (int day = 1; day <= horizon; day++) {
            survivalSum += survival[day];
        }
        double ltv = persona.monthlyPrice() * survivalSum / 30.0;

        return new SegmentResult(
                persona,
                survival,
                temp,
                avgTemp,
                at(survival, 1),
                at(survival, 7),
                at(survival, 30),
                at(survival, 60),
                at(survival, 90),
                ltv
        );
    }

    /**
     * 日流失风险（EXP-BOTS §3 步骤 4）：
     * {@code hazard = baseChurn + kLow·sensitivity·max(0,(lowTemp−T)/span) + (day>fatigueDay ? novelty·kNov : 0)}。
     */
    static double dailyHazard(BotPersona persona, NumericConfig cfg, double t, int day) {
        double lowTempTerm = cfg.kLow() * persona.sensitivity()
                * Math.max(0.0, (persona.lowTemp() - t) / cfg.span());
        double noveltyTerm = day > cfg.fatigueDay() ? persona.novelty() * cfg.kNov() : 0.0;
        double hazard = persona.baseChurn() + lowTempTerm + noveltyTerm;
        if (hazard < 0.0) {
            return 0.0;
        }
        return Math.min(hazard, 1.0);
    }

    private static double at(double[] survival, int day) {
        return day < survival.length ? survival[day] : survival[survival.length - 1];
    }
}
