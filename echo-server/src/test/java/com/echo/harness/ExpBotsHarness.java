package com.echo.harness;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 体验 Bot 数值模拟 Harness 入口（EXP-BOTS §8）。
 *
 * <p>纯内存、不绑 WebSocket：以 §3.12 v1-tentative 基线跑全启用画像，写分群报告
 * （{@code target/harness/report.json} + {@code segments.csv}），并向 stdout 打印摘要。</p>
 *
 * <p>用法：{@code java com.echo.harness.ExpBotsHarness [配置json路径] [输出目录]}。
 * 两参数均可选：缺省用 {@link NumericConfig#defaultTentative()} 与内置画像库、默认输出目录。</p>
 */
public final class ExpBotsHarness {

    private static final Gson GSON = new Gson();

    private ExpBotsHarness() {
    }

    public static void main(String[] args) {
        NumericConfig cfg = (args.length >= 1 && args[0] != null && !args[0].isBlank())
                ? loadConfig(Path.of(args[0]))
                : NumericConfig.defaultTentative();
        Path outDir = (args.length >= 2 && args[1] != null && !args[1].isBlank())
                ? Path.of(args[1])
                : ReportWriter.DEFAULT_OUTPUT_DIR;

        Population population = Population.loadDefault();
        AggregateReport report = run(cfg, population);
        List<Path> files = new ReportWriter().write(report, outDir);
        printSummary(report, files);
    }

    /** 跑一遍模拟并聚合（供 main 与测试复用）。 */
    public static AggregateReport run(NumericConfig cfg, Population population) {
        List<BotPersona> personas = population.enabledNormalized();
        List<SegmentResult> results = new Simulator().simulateAll(personas, cfg);
        double weightedD30 = AggregateReport.weightedD30(results);
        List<Flag> flags = new FlagEngine().evaluate(results, cfg, weightedD30);
        return AggregateReport.build(population.version(), cfg, results, flags);
    }

    static NumericConfig loadConfig(Path path) {
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            NumericConfig cfg = GSON.fromJson(json, NumericConfig.class);
            if (cfg == null) {
                throw new IllegalArgumentException("配置 JSON 解析为空: " + path);
            }
            return cfg;
        } catch (IOException e) {
            throw new UncheckedIOException("读取配置 JSON 失败: " + path, e);
        }
    }

    private static void printSummary(AggregateReport r, List<Path> files) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        sb.append("==== 体验 Bot 数值模拟 Harness 摘要 ====\n");
        sb.append(String.format(Locale.ROOT, "画像库版本: %s | 启用分群: %d%n",
                r.populationVersion(), r.enabledSegments()));
        sb.append(String.format(Locale.ROOT,
                "加权 D30=%.4f | 加权 D90=%.4f | 加权均温=%.2f%n",
                r.weightedD30(), r.weightedD90(), r.weightedAvgTemp()));
        sb.append(String.format(Locale.ROOT,
                "混合 ARPU=%.2f 元/月 | 付费占比=%.1f%% | 红线风险群数=%d%n",
                r.blendedArpu(), r.paidShare() * 100.0, r.redlineRiskGroups()));
        sb.append("---- 分群明细 ----\n");
        sb.append(String.format(Locale.ROOT, "%-4s %-22s %-8s %6s %5s %7s %7s %7s %8s%n",
                "id", "name", "tier", "pets", "wt%", "avgT", "d30", "d90", "ltv"));
        for (SegmentResult s : r.segments()) {
            BotPersona p = s.persona();
            sb.append(String.format(Locale.ROOT, "%-4s %-22s %-8s %6d %5.1f %7.1f %7.3f %7.3f %8.2f%n",
                    p.id(), p.name(), p.tier().name(), p.pets(), p.weight() * 100.0,
                    s.avgTemp(), s.d30(), s.d90(), s.ltv()));
        }
        sb.append("---- 旗标 ----\n");
        if (r.flags().isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (Flag f : r.flags()) {
                sb.append(String.format(Locale.ROOT, "[%s] %s%n", f.severity(), f.message()));
            }
        }
        sb.append("---- 报告文件 ----\n");
        for (Path f : files) {
            sb.append(f.toAbsolutePath()).append('\n');
        }
        sb.append("=====================================\n");
        System.out.print(sb);
    }
}
