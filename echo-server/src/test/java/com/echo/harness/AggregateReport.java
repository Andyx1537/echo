package com.echo.harness;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 大盘聚合报告（EXP-BOTS §4 反馈指标产出）。
 *
 * <p>基于"已归一化权重的启用画像"结果聚合：加权 D30/D90、加权均温、混合 ARPU、付费占比、
 * 红线风险群数，以及分群明细与旗标列表。</p>
 *
 * @param populationVersion 画像库版本
 * @param config            本次数值配置
 * @param enabledSegments   参与大盘的分群数
 * @param weightedD30       加权 D30
 * @param weightedD90       加权 D90
 * @param weightedAvgTemp   加权均温
 * @param blendedArpu       混合 ARPU（Σ weight·月费，元/月）
 * @param paidShare         付费占比（Σ 付费档 weight）
 * @param redlineRiskGroups 红线风险群数（触发 RED 旗标的画像数）
 * @param segments          分群明细
 * @param flags             旗标列表
 */
public record AggregateReport(
        String populationVersion,
        NumericConfig config,
        int enabledSegments,
        double weightedD30,
        double weightedD90,
        double weightedAvgTemp,
        double blendedArpu,
        double paidShare,
        int redlineRiskGroups,
        List<SegmentResult> segments,
        List<Flag> flags
) {

    /** 仅加权 D30（供 {@link FlagEngine} 先行计算，避免旗标与报告循环依赖）。 */
    public static double weightedD30(List<SegmentResult> results) {
        double sum = 0.0;
        for (SegmentResult r : results) {
            sum += r.persona().weight() * r.d30();
        }
        return sum;
    }

    /**
     * 组装报告。
     *
     * @param version 画像库版本
     * @param cfg     数值配置
     * @param results 归一化权重的启用分群结果
     * @param flags   已生成旗标
     */
    public static AggregateReport build(String version, NumericConfig cfg,
                                        List<SegmentResult> results, List<Flag> flags) {
        double wD30 = 0.0;
        double wD90 = 0.0;
        double wTemp = 0.0;
        double arpu = 0.0;
        double paid = 0.0;
        for (SegmentResult r : results) {
            BotPersona p = r.persona();
            double w = p.weight();
            wD30 += w * r.d30();
            wD90 += w * r.d90();
            wTemp += w * r.avgTemp();
            arpu += w * p.monthlyPrice();
            if (p.tier().isPaid()) {
                paid += w;
            }
        }

        Set<String> redGroups = new HashSet<>();
        for (Flag f : flags) {
            if (f.isRed() && f.personaId() != null) {
                redGroups.add(f.personaId());
            }
        }

        return new AggregateReport(version, cfg, results.size(),
                wD30, wD90, wTemp, arpu, paid, redGroups.size(), results, flags);
    }
}
