package com.echo.http.ranking;

/**
 * {@code SURGE} 热度突增召回的准入判定（{@code SPEC-recommendation-ranking §3.9} /
 * {@code TECH-DESIGN §8.6}）。
 *
 * <h2>它是挣回来的，不是给的</h2>
 *
 * <p>已经自然流掉（{@code S4 DRAINED}）的内容，如果<b>突然重新变热</b>，可以被拉回主动分发 24 小时。
 * 🔴 但这不是复活机制——{@code REVIVE} 池已被整体移除，零响应内容满一周即视为吸引力不足，
 * 退出主动分发，只能由搜索/分类/相似进入。两者的区别是判据：
 * {@code REVIVE} 问「它是不是没被看见过」（给），{@code SURGE} 问「它现在是不是真的热」（挣）。</p>
 *
 * <p>🔴 <b>不要用本类去做任何形式的救济。</b>「零响应内容也给一次机会」这种需求，
 * 无论包装成什么名字，都是被推翻的那个设计。</p>
 *
 * <h2>触发条件</h2>
 *
 * <pre>
 * base  = 该卡近 14 天的「日均独立互动者数」
 * surge = 该卡近 24h 的「独立互动者数」
 *
 * 触发 ⟺ surge ≥ 5           ① 绝对地板
 *      AND surge ≥ 3 × base   ② 相对增速
 * </pre>
 *
 * <p>两个条件缺一不可，而且方向相反，这是刻意的<b>反马太</b>设计：
 * 基线 0 的冷内容需要 5 个人，基线 10/日的热内容需要 30 个人。
 * 越是已经有热度的内容，想再拿一次拉回就越难。</p>
 *
 * <h2>🔴 拉回的是「机会」，不是「权重」</h2>
 *
 * <p>进了 {@code SURGE} 通道也<b>不提升排序权重</b>：用的仍是自然衰减后的真实 {@code W}，
 * 24h 内继续下降，且 {@code SURGE} 曝光照常计入 {@code n}。
 * 通道本身限量（每 20 条 ≤1 条，从兜底份额出），也<b>不占</b> 15% 冷启动保底位。</p>
 *
 * <h2>防刷：五道，外加两道结构性上限</h2>
 *
 * <p>本类只做<b>判定</b>，不数人头。前两道（账号去重且 {@code bound=true}、排除作者本人与关联账号）
 * 决定的是 {@code surgeInteractors} 这个数字<b>怎么数出来</b>，属于调用方的责任——
 * 🔴 见 {@link Candidate#surgeInteractors} 的约定，传一个没清洗过的原始计数进来，
 * 后面三道全部形同虚设。</p>
 */
public final class SurgeModel {

    private final SurgeConfig cfg;

    public SurgeModel(SurgeConfig cfg) {
        this.cfg = cfg;
    }

    /** 判定结论。{@code reason} 在拒绝时说明卡在哪一道，用于 {@code rank_surge_reject} 埋点。 */
    public record Decision(boolean admitted, Reason reason, double ratio) {
        public static Decision reject(Reason reason, double ratio) {
            return new Decision(false, reason, ratio);
        }
    }

    /**
     * 拒绝原因。
     *
     * <p>⚠️ 规格给了五道防刷约束，但<b>没有给出 {@code rank_surge_reject.reason} 的枚举字面量</b>
     * （只示例了 {@code follow_ratio}）。这里的字面量是本实现拟定的，
     * 见 {@code docs/OPEN-QUESTIONS-ranking.md} Q8——若规格后续给出正式枚举，以规格为准。</p>
     */
    public enum Reason {
        /** 通过。 */
        ADMITTED("admitted"),
        /** 不在 S4：只有自然流掉的内容才谈得上"拉回"。 */
        NOT_DRAINED("not_drained"),
        /** 终身 2 次已用完。 */
        LIFETIME_EXHAUSTED("lifetime_exhausted"),
        /** 距上次触发不满 30 天。 */
        IN_COOLDOWN("in_cooldown"),
        /** 该作者名下 7 天内已经触发过一次。 */
        AUTHOR_WEEKLY_CAP("author_weekly_cap"),
        /** 近 24h 独立互动者数没到绝对地板。 */
        BELOW_ABS_FLOOR("below_abs_floor"),
        /** 没到基线的 3 倍——热，但不是"突然"热。 */
        BELOW_RATIO("below_ratio"),
        /** 互动者里与作者有关注关系的占比过半。 */
        FOLLOW_RATIO("follow_ratio"),
        /** 命中互刷环检测。 */
        MUTUAL_BOOST_RING("mutual_boost_ring");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        /** 埋点字面量。 */
        public String code() {
            return code;
        }
    }

    /**
     * 一个待判定的候选。
     *
     * @param pool                    当前生命周期池；只有 {@code DRAINED} 有资格
     * @param surgeInteractors        近 24h <b>独立互动者数</b>。🔴 调用方必须<b>先</b>做完：
     *                                按账号去重、只算 {@code bound=true} 的账号、
     *                                剔除作者本人与其关联账号。这三件事在这里做不了，
     *                                而没做的话下面几道防刷都白搭
     * @param dailyBaseline           近 14 天日均独立互动者数（同上清洗口径）
     * @param followLinkedInteractors 上述互动者中，与作者存在任一方向关注关系的人数
     * @param mutualBoostRing         是否命中互刷环检测（{@code §6.5}）
     * @param lifetimeTriggers        这张卡此前已触发过几次
     * @param lastTriggeredAt         这张卡上次触发时刻；从未触发传 0
     * @param lastAuthorTriggeredAt   <b>该作者名下任一内容</b>上次触发时刻；从未触发传 0
     */
    public record Candidate(
            String cardId,
            long authorId,
            String pool,
            int surgeInteractors,
            double dailyBaseline,
            int followLinkedInteractors,
            boolean mutualBoostRing,
            int lifetimeTriggers,
            long lastTriggeredAt,
            long lastAuthorTriggeredAt) {
    }

    /** 只有自然流掉的内容才谈得上被"拉回"。 */
    public static final String POOL_DRAINED = "DRAINED";

    /**
     * 判一个候选能不能进 {@code SURGE}。
     *
     * <p>检查顺序是有意的：先结构性资格（在不在 S4、次数与冷却用完没有），
     * 再热度门槛，<b>最后</b>才是防刷。这样 {@code rank_surge_reject} 的原因分布才有意义——
     * 防刷类的拒绝只会落在「本来够格」的候选上，那个数字才是「有多少人在刷」，
     * 否则它会被大量根本不够热的候选淹掉。</p>
     */
    public Decision evaluate(Candidate c, long now) {
        double ratio = c.dailyBaseline() <= 0
                ? Double.POSITIVE_INFINITY
                : c.surgeInteractors() / c.dailyBaseline();

        if (!POOL_DRAINED.equals(c.pool())) {
            return Decision.reject(Reason.NOT_DRAINED, ratio);
        }
        if (c.lifetimeTriggers() >= SurgeConfig.LIFETIME_MAX_TRIGGERS) {
            return Decision.reject(Reason.LIFETIME_EXHAUSTED, ratio);
        }
        if (c.lastTriggeredAt() > 0
                && now - c.lastTriggeredAt() < SurgeConfig.COOLDOWN.toMillis()) {
            return Decision.reject(Reason.IN_COOLDOWN, ratio);
        }
        if (c.lastAuthorTriggeredAt() > 0
                && now - c.lastAuthorTriggeredAt() < SurgeConfig.AUTHOR_WINDOW.toMillis()) {
            return Decision.reject(Reason.AUTHOR_WEEKLY_CAP, ratio);
        }
        if (c.surgeInteractors() < cfg.absFloor()) {
            return Decision.reject(Reason.BELOW_ABS_FLOOR, ratio);
        }
        if (c.surgeInteractors() < cfg.ratio() * c.dailyBaseline()) {
            return Decision.reject(Reason.BELOW_RATIO, ratio);
        }
        if (followLinkedShare(c) > SurgeConfig.MAX_FOLLOW_LINKED_SHARE) {
            // 热度主要来自作者自己的关注者 = 这不是"被更多人发现了"，是"熟人来捧场"
            return Decision.reject(Reason.FOLLOW_RATIO, ratio);
        }
        if (c.mutualBoostRing()) {
            return Decision.reject(Reason.MUTUAL_BOOST_RING, ratio);
        }
        return new Decision(true, Reason.ADMITTED, ratio);
    }

    private static double followLinkedShare(Candidate c) {
        if (c.surgeInteractors() <= 0) {
            return 0.0;
        }
        return (double) c.followLinkedInteractors() / c.surgeInteractors();
    }

    /** 拉回窗口的到期时刻。 */
    public long expiresAt(long triggeredAt) {
        return triggeredAt + cfg.ttlHours() * 3600_000L;
    }

    /**
     * 通道内排序键：{@code surge / base} 降序（{@code §8.6.6}）。
     *
     * <p>🔴 用增速而不是绝对热度排，否则通道内部会重新变成一个小热榜——
     * 而「谁最热」正是这个产品明确不做的东西。</p>
     */
    public static java.util.Comparator<Decision> channelOrder() {
        return java.util.Comparator.comparingDouble(Decision::ratio).reversed();
    }
}
