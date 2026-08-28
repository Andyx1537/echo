package com.echo.harness;

import java.util.ArrayList;
import java.util.List;

/**
 * 旗标引擎（EXP-BOTS §4 反馈指标 · §5 收敛判据）。
 *
 * <p>依据分群结果生成红线/痛点旗标：
 * <ul>
 *   <li><b>红线</b>：{@code grief} 画像均温贴地板（avgTemp &lt; lowTemp）或 D30 骤跌（d30 &lt; 0.5）；</li>
 *   <li><b>多宠欠维系</b>：多宠画像长期低温（avgTemp &lt; 安好线 75）→ 大面积欠维系挫败；</li>
 *   <li><b>高级档秒满</b>：premium 画像 avgTemp &gt; 98 → 无成长感（失去养成）；</li>
 *   <li><b>大盘偏低</b>：加权 D30 低于目标带下限（默认 0.30）。</li>
 * </ul>
 */
public final class FlagEngine {

    /** 安好档下界（75）：低于此视为长期低温。 */
    private static final double HEALTHY_TEMP = 75.0;
    /** 红线 D30 阈值。 */
    private static final double GRIEF_D30_MIN = 0.5;
    /** 高级档"秒满"温度阈值。 */
    private static final double PREMIUM_SATURATED_TEMP = 98.0;

    private final double targetD30Floor;

    /** 默认大盘 D30 目标带下限 = 0.30（§8.3 占位）。 */
    public FlagEngine() {
        this(0.30);
    }

    public FlagEngine(double targetD30Floor) {
        this.targetD30Floor = targetD30Floor;
    }

    /**
     * 生成旗标。
     *
     * @param results     分群结果
     * @param cfg         数值配置
     * @param weightedD30 加权大盘 D30
     */
    public List<Flag> evaluate(List<SegmentResult> results, NumericConfig cfg, double weightedD30) {
        List<Flag> flags = new ArrayList<>();

        for (SegmentResult r : results) {
            BotPersona p = r.persona();

            // 🚨 红线：哀伤敏感群体
            if (p.grief()) {
                if (r.avgTemp() < p.lowTemp()) {
                    flags.add(Flag.red("GRIEF_LOW_TEMP", p.id(),
                            "红线：哀伤敏感群 [%s] 均温 %.1f 低于低温阈 %.1f，二次伤害风险，需回调数值。"
                                    .formatted(p.name(), r.avgTemp(), p.lowTemp())));
                }
                if (r.d30() < GRIEF_D30_MIN) {
                    flags.add(Flag.red("GRIEF_D30_DROP", p.id(),
                            "红线：哀伤敏感群 [%s] D30=%.2f 骤跌（<%.2f），挫败流失风险。"
                                    .formatted(p.name(), r.d30(), GRIEF_D30_MIN)));
                }
            }

            // 多宠欠维系低温挫败
            if (p.pets() > 1 && r.avgTemp() < HEALTHY_TEMP) {
                flags.add(Flag.warn("MULTIPET_UNDERMAINTAINED", p.id(),
                        "痛点：多宠画像 [%s]（%d 宠）均温 %.1f 长期贴低温，欠维系挫败。"
                                .formatted(p.name(), p.pets(), r.avgTemp())));
            }

            // 高级档秒满无成长感
            if (p.tier() == Tier.PREMIUM && r.avgTemp() > PREMIUM_SATURATED_TEMP) {
                flags.add(Flag.warn("PREMIUM_SATURATED", p.id(),
                        "痛点：高级档 [%s] 均温 %.1f 近乎秒满（>%.1f），失去养成/增长感。"
                                .formatted(p.name(), r.avgTemp(), PREMIUM_SATURATED_TEMP)));
            }
        }

        // 大盘 D30 偏低
        if (weightedD30 < targetD30Floor) {
            flags.add(Flag.warn("AGGREGATE_D30_LOW", null,
                    "痛点：大盘加权 D30=%.3f 低于目标带下限 %.2f，中低频群断崖流失风险。"
                            .formatted(weightedD30, targetD30Floor)));
        }

        return flags;
    }
}
