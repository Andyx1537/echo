package com.echo.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 蒙特卡洛模拟器（EXP-BOTS §6 可扩展挂点，默认关闭）。
 *
 * <p>用随机数决定每日是否打开（伯努利 pOpen）等个体差异，跑 N 条采样再取均值，替换期望值近似。
 * 本期仅提供骨架 + 可跑：带 {@code long seed}、{@code runs} 开关；默认不接入大盘报告。</p>
 */
public final class MonteCarloSimulator {

    private final long seed;
    private final int runs;
    private final boolean enabled;

    public MonteCarloSimulator(long seed, int runs, boolean enabled) {
        this.seed = seed;
        this.runs = Math.max(1, runs);
        this.enabled = enabled;
    }

    /** 默认关闭：seed=0，runs=1。 */
    public static MonteCarloSimulator disabled() {
        return new MonteCarloSimulator(0L, 1, false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long seed() {
        return seed;
    }

    public int runs() {
        return runs;
    }

    /** 对一组画像跑蒙特卡洛，返回逐画像的均值结果。 */
    public List<SegmentResult> simulateAll(List<BotPersona> personas, NumericConfig cfg) {
        List<SegmentResult> out = new ArrayList<>(personas.size());
        for (BotPersona p : personas) {
            out.add(simulate(p, cfg));
        }
        return out;
    }

    /**
     * 单画像蒙特卡洛：N 条随机路径的温度/生存率逐日均值。
     *
     * <p>每天以 pOpen 伯努利决定"是否打开"：打开则当日每宠暖意拉满，否则为 0；温度按 {@link TemperatureModel} 单步演化。</p>
     */
    public SegmentResult simulate(BotPersona persona, NumericConfig cfg) {
        int horizon = cfg.horizon();
        double[] tempAcc = new double[horizon + 1];
        double[] survAcc = new double[horizon + 1];

        double openHeartsPerPet = persona.dailyWarmthCap(cfg) / Math.max(1, persona.pets());
        Random rng = new Random(seed);

        for (int run = 0; run < runs; run++) {
            double t = NumericConfig.CEILING;
            double s = 1.0;
            tempAcc[0] += t;
            survAcc[0] += s;
            for (int day = 1; day <= horizon; day++) {
                boolean opened = rng.nextDouble() < persona.pOpen();
                double hearts = opened ? openHeartsPerPet : 0.0;
                t = TemperatureModel.step(t, hearts, cfg);
                double hazard = Simulator.dailyHazard(persona, cfg, t, day);
                boolean churned = rng.nextDouble() < hazard;
                if (churned) {
                    s = 0.0;
                }
                tempAcc[day] += t;
                survAcc[day] += s;
            }
        }

        double[] temp = new double[horizon + 1];
        double[] survival = new double[horizon + 1];
        double tempSum = 0.0;
        double survivalSum = 0.0;
        for (int day = 0; day <= horizon; day++) {
            temp[day] = tempAcc[day] / runs;
            survival[day] = survAcc[day] / runs;
            tempSum += temp[day];
            if (day >= 1) {
                survivalSum += survival[day];
            }
        }
        double avgTemp = tempSum / (horizon + 1);
        double ltv = persona.monthlyPrice() * survivalSum / 30.0;

        return new SegmentResult(persona, survival, temp, avgTemp,
                survival[Math.min(1, horizon)], survival[Math.min(7, horizon)],
                survival[Math.min(30, horizon)], survival[Math.min(60, horizon)],
                survival[Math.min(90, horizon)], ltv);
    }
}
