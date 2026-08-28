package com.echo.harness;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 报告输出器（EXP-BOTS §8.2）：写 {@code report.json}（gson pretty）与 {@code segments.csv}（纯 Java）。
 *
 * <p>默认输出目录 {@code target/harness/}。JSON 采用精简视图（不含逐日曲线大数组），便于人读与 diff。</p>
 */
public final class ReportWriter {

    /** 默认输出目录。 */
    public static final Path DEFAULT_OUTPUT_DIR = Path.of("target", "harness");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String CSV_HEADER = "id,name,weight,tier,pets,avgTemp,d30,d60,d90,ltv";

    /** 写 report.json 与 segments.csv 到默认目录，返回写出的文件列表。 */
    public List<Path> write(AggregateReport report) {
        return write(report, DEFAULT_OUTPUT_DIR);
    }

    /** 写 report.json 与 segments.csv 到指定目录，返回写出的文件列表。 */
    public List<Path> write(AggregateReport report, Path outDir) {
        try {
            Files.createDirectories(outDir);
            Path json = outDir.resolve("report.json");
            Path csv = outDir.resolve("segments.csv");
            Files.writeString(json, GSON.toJson(toView(report)), StandardCharsets.UTF_8);
            Files.writeString(csv, toCsv(report), StandardCharsets.UTF_8);
            return List.of(json, csv);
        } catch (IOException e) {
            throw new UncheckedIOException("写出 harness 报告失败: " + outDir, e);
        }
    }

    private static ReportView toView(AggregateReport r) {
        List<SegmentView> segs = new ArrayList<>(r.segments().size());
        for (SegmentResult s : r.segments()) {
            BotPersona p = s.persona();
            segs.add(new SegmentView(p.id(), p.name(), round(p.weight(), 4), p.tier().name(),
                    p.pets(), p.grief(), round(s.avgTemp(), 2), round(s.d1(), 4), round(s.d7(), 4),
                    round(s.d30(), 4), round(s.d60(), 4), round(s.d90(), 4),
                    round(s.finalTemp(), 2), round(s.ltv(), 2)));
        }
        Summary summary = new Summary(r.enabledSegments(), round(r.weightedD30(), 4),
                round(r.weightedD90(), 4), round(r.weightedAvgTemp(), 2),
                round(r.blendedArpu(), 2), round(r.paidShare(), 4), r.redlineRiskGroups());
        return new ReportView(r.populationVersion(), r.config(), summary, segs, r.flags());
    }

    private static String toCsv(AggregateReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append(CSV_HEADER).append('\n');
        for (SegmentResult s : r.segments()) {
            BotPersona p = s.persona();
            sb.append(esc(p.id())).append(',')
                    .append(esc(p.name())).append(',')
                    .append(fmt(p.weight())).append(',')
                    .append(esc(p.tier().name())).append(',')
                    .append(p.pets()).append(',')
                    .append(fmt(s.avgTemp())).append(',')
                    .append(fmt(s.d30())).append(',')
                    .append(fmt(s.d60())).append(',')
                    .append(fmt(s.d90())).append(',')
                    .append(fmt(s.ltv())).append('\n');
        }
        return sb.toString();
    }

    /** 纯 Java CSV 字段转义：含逗号/引号/换行时用双引号包裹并转义内部引号。 */
    private static String esc(String field) {
        if (field == null) {
            return "";
        }
        boolean needsQuote = field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0;
        if (!needsQuote) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    private static double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }

    // ---- JSON 视图（精简，不含逐日曲线大数组）----

    private record ReportView(String populationVersion, NumericConfig config, Summary summary,
                              List<SegmentView> segments, List<Flag> flags) {
    }

    private record Summary(int enabledSegments, double weightedD30, double weightedD90,
                           double weightedAvgTemp, double blendedArpu, double paidShare,
                           int redlineRiskGroups) {
    }

    private record SegmentView(String id, String name, double weight, String tier, int pets,
                               boolean grief, double avgTemp, double d1, double d7, double d30,
                               double d60, double d90, double finalTemp, double ltv) {
    }
}
