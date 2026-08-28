package com.echo.http.ranking;

import java.util.Arrays;
import java.util.function.Function;

/**
 * {@code B1} 负反馈制动的二维档位表（后台可配）。
 *
 * <p>两个维度：<b>累计投放次数 {@code n}</b> × <b>拒绝率</b>，格子里是 {@code brakeFactor} 的
 * <b>目标值</b>（不是乘数）。取表之后必须走
 * {@link BrakeModel#ratchet} 的棘轮，不能直接赋值。</p>
 *
 * <pre>
 *  n \ 拒绝率    ≥8%    ≥20%   ≥33%
 *  ≥40          0.90   0.75   0.60
 *  ≥80          0.80   0.65   0.50
 *  ≥120         0.70   0.55   0.40
 * </pre>
 *
 * <p>拒绝率每升一档减 <b>0.15</b>（制作人定稿：横向步长加大，让拒绝率咬得更狠）。
 * 每格实际值多少次投放、多少小时，见 {@code docs/BRAKE-CALIBRATION.md} §2——
 * 🔴 <b>这张表以后是运营在调，调之前先看那张代价表</b>，
 * 格子里的小数本身看不出"少推多少"。</p>
 *
 * <p>{@code n < 40} 或 拒绝率 {@code < 8%} 落在表外，不制动（目标值 1.0）。</p>
 *
 * <h2>🔴 为什么必须在两个维度上都单调不增</h2>
 *
 * <p>整个制动机制的代数保证是「{@code brakeFactor} 只减不增」。棘轮
 * （{@code min(当前值, 查表值)}）本身已经拦住了回升，但<b>那是运行时的补丁，不是表的性质</b>。
 * 一张非单调的表意味着「情况更糟时惩罚更轻」，即使棘轮挡住了数值回升，
 * 这张表本身也已经在表达一个说不通的策略。</p>
 *
 * <p>而它<b>在报表上看不出来</b>：填错一个格子不会报错、不会掉用例，
 * 只会让某一档的内容悄悄比它应得的多推一点。所以校验放在启动时，不满足直接起不来。</p>
 */
public record BrakeTable(int[] exposureTiers, double[] rejectionTiers, double[][] values) {

    /** 落在表外时的目标值：不制动。 */
    public static final double NO_BRAKE = 1.0;

    /**
     * 定稿的初值表（制作人 v0.6 拍板）。
     *
     * <p>⚠️ 这组数<b>是初值不是结论</b>：即便按大步长排完，最轻的一格（0.90）也只值
     * 3.5 次投放，而无制动的卡能拿到 99 次。建这张表是为了先把可配的机制立起来，
     * 等真实拒绝率分布出来再调，调的依据是 {@link BrakeMetrics}。</p>
     */
    public static final BrakeTable SEED = new BrakeTable(
            new int[]{40, 80, 120},
            new double[]{0.08, 0.20, 0.33},
            new double[][]{
                    {0.90, 0.75, 0.60},
                    {0.80, 0.65, 0.50},
                    {0.70, 0.55, 0.40},
            });

    public BrakeTable {
        validate(exposureTiers, rejectionTiers, values);
        exposureTiers = exposureTiers.clone();
        rejectionTiers = rejectionTiers.clone();
        values = deepCopy(values);
    }

    /**
     * 查表：给定投放次数与拒绝率，得到 {@code brakeFactor} 的目标值。
     *
     * <p>取<b>命中的最高档</b>：{@code n} 满足哪几档就取次数最多的那档，拒绝率同理。
     * 两个维度独立取档，交叉出一个格子。</p>
     *
     * @return 目标值；落在表外返回 {@link #NO_BRAKE}
     */
    public double lookup(int n, double rejectionRate) {
        int row = exposureTier(n);
        int col = rejectionTier(rejectionRate);
        if (row < 0 || col < 0) {
            return NO_BRAKE;
        }
        return values[row][col];
    }

    /** 命中的最高投放次数档；一档都没到返回 -1。 */
    private int exposureTier(int n) {
        int idx = -1;
        for (int i = 0; i < exposureTiers.length; i++) {
            if (n >= exposureTiers[i]) {
                idx = i;
            }
        }
        return idx;
    }

    /** 命中的最高拒绝率档；一档都没到返回 -1。 */
    private int rejectionTier(double rate) {
        int idx = -1;
        for (int i = 0; i < rejectionTiers.length; i++) {
            if (rate >= rejectionTiers[i]) {
                idx = i;
            }
        }
        return idx;
    }

    private static double[] toDouble(int[] a) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i];
        }
        return out;
    }

    /** 表外最低的那道门槛：{@code n} 没到它就完全不评估制动。 */
    public int minExposures() {
        return exposureTiers[0];
    }

    /** 表外最低的那道门槛：拒绝率没到它就完全不制动。 */
    public double minRejectionRate() {
        return rejectionTiers[0];
    }

    // ------------------------------------------------------------------ 校验

    private static void validate(int[] exposureTiers, double[] rejectionTiers, double[][] values) {
        if (exposureTiers == null || rejectionTiers == null || values == null
                || exposureTiers.length == 0 || rejectionTiers.length == 0) {
            throw new IllegalStateException("制动档位表配置非法：维度不能为空");
        }
        if (values.length != exposureTiers.length) {
            throw new IllegalStateException("制动档位表配置非法：行数 " + values.length
                    + " 与投放次数档数 " + exposureTiers.length + " 不符");
        }
        for (double[] row : values) {
            if (row.length != rejectionTiers.length) {
                throw new IllegalStateException("制动档位表配置非法：列数 " + row.length
                        + " 与拒绝率档数 " + rejectionTiers.length + " 不符");
            }
        }
        requireStrictlyAscending(toDouble(exposureTiers), "投放次数档位");
        requireStrictlyAscending(rejectionTiers, "拒绝率档位");

        for (double[] row : values) {
            for (double v : row) {
                if (!(v > 0.0 && v <= 1.0)) {
                    throw new IllegalStateException(
                            "制动档位表配置非法：格子值必须落在 (0,1]，制动只能让权重变小：" + v);
                }
            }
        }
        // 🔴 两个维度都必须单调不增
        for (int i = 0; i < values.length; i++) {
            for (int j = 1; j < values[i].length; j++) {
                if (values[i][j] > values[i][j - 1]) {
                    throw new IllegalStateException(String.format(
                            "制动档位表配置非法：第 %d 行沿拒绝率维回升（%.4f → %.4f）。"
                                    + "拒绝率更高却制动更轻，说不通",
                            i + 1, values[i][j - 1], values[i][j]));
                }
            }
        }
        for (int j = 0; j < values[0].length; j++) {
            for (int i = 1; i < values.length; i++) {
                if (values[i][j] > values[i - 1][j]) {
                    throw new IllegalStateException(String.format(
                            "制动档位表配置非法：第 %d 列沿投放次数维回升（%.4f → %.4f）。"
                                    + "投得更多却制动更轻，说不通",
                            j + 1, values[i - 1][j], values[i][j]));
                }
            }
        }
    }

    private static void requireStrictlyAscending(double[] tiers, String what) {
        for (int i = 1; i < tiers.length; i++) {
            if (tiers[i] <= tiers[i - 1]) {
                throw new IllegalStateException("制动档位表配置非法：" + what
                        + "必须严格递增：" + Arrays.toString(tiers));
            }
        }
    }

    private static double[][] deepCopy(double[][] src) {
        double[][] out = new double[src.length][];
        for (int i = 0; i < src.length; i++) {
            out[i] = src[i].clone();
        }
        return out;
    }

    // ------------------------------------------------------------------ 配置

    /**
     * 从 {@code ECHO_BRAKE_TABLE} 读表，未配置则用 {@link #SEED}。
     *
     * <p>格式：{@code 次数档|拒绝率档|行1;行2;行3}，例如
     * {@code 40,80,120|0.08,0.20,0.33|0.90,0.85,0.80;0.80,0.75,0.70;0.70,0.65,0.60}</p>
     */
    public static BrakeTable fromEnv() {
        return from(System::getenv);
    }

    public static BrakeTable from(Function<String, String> env) {
        String raw = env.apply("ECHO_BRAKE_TABLE");
        if (raw == null || raw.isBlank()) {
            return SEED;
        }
        String[] parts = raw.split("\\|");
        if (parts.length != 3) {
            throw new IllegalStateException(
                    "ECHO_BRAKE_TABLE 格式非法，应为「次数档|拒绝率档|行;行;行」：" + raw);
        }
        try {
            int[] exposures = Arrays.stream(parts[0].split(",")).map(String::trim)
                    .mapToInt(Integer::parseInt).toArray();
            double[] rates = Arrays.stream(parts[1].split(",")).map(String::trim)
                    .mapToDouble(Double::parseDouble).toArray();
            String[] rows = parts[2].split(";");
            double[][] values = new double[rows.length][];
            for (int i = 0; i < rows.length; i++) {
                values[i] = Arrays.stream(rows[i].split(",")).map(String::trim)
                        .mapToDouble(Double::parseDouble).toArray();
            }
            return new BrakeTable(exposures, rates, values);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("ECHO_BRAKE_TABLE 里有读不懂的数字：" + raw, e);
        }
    }

    /** 便于把生效的表打进启动日志——可配的东西必须能在日志里看到实际生效值。 */
    public String render() {
        StringBuilder sb = new StringBuilder("制动档位表 brakeFactor 目标值：\n");
        sb.append(String.format("%-10s", "n \\ 拒绝率"));
        for (double r : rejectionTiers) {
            sb.append(String.format("  ≥%-6s", String.format("%.0f%%", r * 100)));
        }
        sb.append('\n');
        for (int i = 0; i < exposureTiers.length; i++) {
            sb.append(String.format("≥%-9d", exposureTiers[i]));
            for (int j = 0; j < rejectionTiers.length; j++) {
                sb.append(String.format("  %-7.2f", values[i][j]));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
