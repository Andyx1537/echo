package com.echo.http.ranking;

/**
 * 负反馈制动（{@code TECH-DESIGN §8.5}，{@code B1} 已按制作人裁定整条重做）。
 *
 * <h2>🔴 互动量是刹车，不是油门</h2>
 *
 * <p>这里没有任何一条路径会让权重变大。全部制动效果都落在 {@code brakeFactor} 上，
 * 而它值域 (0,1]、初值 1.0、<b>只减不增</b>。一千次记得与零互动，累计曝光的差别应当在 5% 以内
 * （{@code TC-WEIGHT-01}）——喜欢它的人再多，也换不来多一次曝光。</p>
 *
 * <h2>🔴 制动不再动 {@code γ}</h2>
 *
 * <p>旧设计是「负反馈率 ≥8% 时把 {@code γ} 由 0.97 加重为 0.90」。该形态已废弃：
 * {@code γ} 恒为 0.97，制动全部落在 {@code brakeFactor}。这样同时解掉三个问题：</p>
 *
 * <ol>
 *   <li>「加重是追溯全历史还是从此刻起分段」——这个问题<b>不存在了</b>。
 *       {@code γⁿ} 里的 {@code γ} 永远是同一个数，不需要记录在第几次换的档；</li>
 *   <li>{@code brakeFactor} 结构上只减不增，与「持久不恢复」的裁定天然自洽；</li>
 *   <li>卡上不需要持久化「换档历史」，只需要一个 {@code brakeFactor} 列。</li>
 * </ol>
 *
 * <h2>🔴 棘轮：{@code brakeFactor = min(当前值, 查表值)}</h2>
 *
 * <p>拒绝率用<b>累计口径</b>（分母是累计 {@code n}），所以它会随 {@code n} 增大而<b>机械地下降</b>——
 * 同样多的「不看」，投得越多算出来的率越低。如果直接按表赋值，已经降下去的档会自动升回来。
 * 那既违反「只减不增」的代数保证，也违反「持久不恢复」的裁定。
 * 所以取表之后必须走 {@link #ratchet}，见 {@code BrakeModelTest} 里那条专门的序列用例。</p>
 *
 * <h2>🔴 零回应不制动</h2>
 *
 * <p>没有正反馈<b>也</b>没有负反馈时，不踩刹车，按 {@code γ} 与 {@code T} 自然衰减就好
 * （{@code §8.5.1}）。「没人理」与「有人明确不想看」是两件事：前者可能只是还没轮到它，
 * 后者才是信号。把前者当负反馈处理，等于让冷启动阶段的运气差变成永久性惩罚。</p>
 *
 * <h2>🔴 降档 ≠ 拿下：{@code B1} 与 {@code B3} 是两条路</h2>
 *
 * <p>原 {@code B1} 二档「拒绝率 ≥20% 直接终止、退出全部主动分发」<b>已取消</b>，
 * 20% 与 33% 现在只是表里的两列，继续走降档。</p>
 *
 * <p>⚠️ <b>但 {@code B3} 不动</b>：违规 / 收回 / 审核改判仍然<b>立即终止</b>，走审核链路
 * （转 {@code RESTRICTED}）。看到「举报也不立即终止了」而以为出了漏子的人，
 * 请注意这两条管的不是一回事：</p>
 *
 * <table>
 *   <tr><th></th><th>{@code B1} 降档</th><th>{@code B3} 终止</th></tr>
 *   <tr><td>管什么</td><td>很多人划走 —— <b>口味问题</b></td><td>这东西不该在这儿 —— <b>合规问题</b></td></tr>
 *   <tr><td>谁判</td><td>分发侧按拒绝率自动判</td><td>审核链路人工判</td></tr>
 *   <tr><td>后果</td><td>推得少一点，仍在流里</td><td>退出全部主动分发</td></tr>
 * </table>
 *
 * <p>举报<b>确实</b>计入 {@code B1} 的拒绝率分子，但那只是「有人不想看」的一种表达；
 * 举报能不能把内容拿下，由审核判，不由分发判。</p>
 */
public final class BrakeModel {

    /** {@code B2}：作者关闭全部回应通道 → {@code brakeFactor × 0.30}。 */
    public static final double B2_CHANNELS_CLOSED_FACTOR = 0.30;

    private final BrakeTable table;

    public BrakeModel(BrakeTable table) {
        this.table = table;
    }

    public BrakeTable table() {
        return table;
    }

    /**
     * 拒绝率 =（「不看」+ 拉黑 + 举报）÷ {@code n}，<b>累计口径</b>。
     *
     * <p>🔴 分母是 {@code n}（已获得的分发机会），不是互动总数。用互动总数当分母会让
     * 「只有两个人看过、其中一个点了不看」算出 50%。</p>
     *
     * <p>⚠️ 累计分母的已知性质：同样多的负反馈，{@code n} 越大算出来的率越低。
     * 制作人已知情并接受，代价由 {@link #ratchet} 的棘轮兜住。</p>
     */
    public static double rejectionRate(int dontShowCount, int blockCount, int reportCount, int n) {
        if (n <= 0) {
            return 0.0;
        }
        return (double) (dontShowCount + blockCount + reportCount) / n;
    }

    /**
     * 查表得到目标 {@code brakeFactor}。落在表外（投放不够多或拒绝率不够高）返回 1.0。
     *
     * <p>🔴 <b>这个返回值不能直接赋给卡</b>，必须经 {@link #ratchet}。</p>
     */
    public double target(int n, double rejectionRate) {
        return table.lookup(n, rejectionRate);
    }

    /**
     * 棘轮：只允许往下走。
     *
     * @param current 卡上当前的 {@code brakeFactor}
     * @param target  {@link #target} 查出来的目标值
     * @return {@code min(current, target)}
     */
    public static double ratchet(double current, double target) {
        return Math.min(current, target);
    }

    /**
     * 一步到位：按当前计数算率、查表、上棘轮。
     *
     * @param currentBrakeFactor 卡上当前的 {@code brakeFactor}
     * @return 落库用的新 {@code brakeFactor}，保证 {@code ≤ currentBrakeFactor}
     */
    public double advance(double currentBrakeFactor, int dontShowCount, int blockCount,
                          int reportCount, int n) {
        double rate = rejectionRate(dontShowCount, blockCount, reportCount, n);
        return ratchet(currentBrakeFactor, target(n, rate));
    }

    /**
     * {@code B2}：作者关闭全部回应通道后的制动。
     *
     * <p>⚠️ <b>这是状态转换动作，调用方必须保证只在「关闭」这一刻调一次。</b>
     * 本方法每调一次就再乘一次 0.30，连调两次会得到 0.09。
     * 「重开通道后是否恢复」按「只减不增」应当不恢复，但规格没有明说——
     * 见 {@code docs/OPEN-QUESTIONS-ranking.md} Q9。</p>
     *
     * <p>与 {@code B1} 的组合是安全的：{@code B2} 把值压到 0.30 之后，
     * {@code B1} 的棘轮 {@code min(0.30, 查表值)} 不会把它抬回去。</p>
     */
    public static double applyChannelsClosed(double brakeFactor) {
        return brakeFactor * B2_CHANNELS_CLOSED_FACTOR;
    }

    /**
     * 🔴 零回应<b>不</b>制动（{@code §8.5.1}）。
     *
     * @return true 表示这张卡处于「没人喜欢也没人讨厌」的状态，此时不得踩刹车
     */
    public static boolean isSilent(int positiveCount, int negativeCount) {
        return positiveCount == 0 && negativeCount == 0;
    }
}
