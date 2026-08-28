package com.echo.http;

/**
 * 往宠温度规则（PRD §3.11 / API-CONTRACT §3，服务端权威）。
 *
 * <p>三条硬规则：</p>
 * <ol>
 *   <li><b>地板 60%</b>：正常态温度不低于 {@link #FLOOR}，任何计算结果 clamp 到 [60,100]。
 *       🔴 <b>永不失去它</b>——这条从未变过。</li>
 *   <li><b>会自然回落，回落到地板为止</b>：免费用户无回访时按 {@code kDecay} <b>缓慢回落</b>至
 *       免费地板（本期 60）；订阅期<b>保持当前刻度不回落</b>。
 *       ⚠️ 🔴 <b>回落不是惩罚式扣分</b>：它没有触发条件、不针对某次缺席、不出现在任何文案里，
 *       更不会让温度掉出地板（守 {@code COPY-GUIDE} 不制造内疚）。</li>
 *   <li><b>只由主人 1v1 回访/陪伴驱动回暖</b>：外部献花完全不改温度（定案 #5，与献花接口解耦）。</li>
 * </ol>
 *
 * <h2>🔴 2026-08-27 更正：第 2 条原先写反了</h2>
 *
 * <p>本类原先写的是「<b>不惩罚式衰减</b>：不因主人『没来』而扣温度——无回访即保持不变，
 * <b>绝不下探</b>」。这与现行裁定相反，已按 {@code API-CONTRACT §3}（2026-08-25 制作人裁定，
 * {@code DECISIONS TM1}）改写。</p>
 *
 * <p><b>推翻理由</b>（🔴 只写结论下一个人会想把它改回去，所以理由必须留在这里）：
 * 原判断只看到「回落 = 加剧负面情绪」这一面，🔴 <b>漏了另一面——回落 = 温度有条件 ⇒
 * 才能否定官方点名的「无条件陪伴」风险画像</b>。⚠️ <b>一个永不下探的温度实质上就是无条件的</b>，
 * 它不是那条抗辩的强项，反而是弱项。</p>
 *
 * <p>⚠️ 🔴 <b>另一件一并记住的事，与裁定本身无关</b>：被删掉那一行原本自称「依据
 * {@code PRD §3.11}」，而 {@code PRD §3.11} 一直写的是「长期不来缓慢回落」——
 * 🔴 <b>它引用了一份说着相反话的文档，而且看起来有理有据。</b>
 * 本次改完之后那句引用才第一次变成真的。<b>不要把「有引用」当成「引用是对的」。</b></p>
 *
 * <h2>⚠️ 回落尚未实现，且实现时有一条硬约束</h2>
 *
 * <p>本类目前<b>只有回暖没有回落</b>：{@link #onOwnerVisit} 单向朝天花板走，
 * 没有任何按时间衰减的入口。🔴 <b>上面第 2 条是裁定，不是本类的现状描述。</b></p>
 *
 * <p>🔴 <b>实现 {@code kDecay} 时的硬约束</b>：免费地板是 {@code TEMP_FLOOR_FREE}，
 * <b>必须写成配置项</b>（配置台校验 {@code >= 60}），🔴 <b>不得实现成对 {@link #FLOOR}
 * 的复用或别名</b>。⚠️ 两者本期取值<b>恰好都是 60</b>，所以别名今天一个 bug 都不产生——
 * 而这正是它危险的地方：等到运营把免费地板配成 70，别名会让绝对硬下界跟着动，
 * 🔴 <b>「永不失去它」那条保证会在没人改动它的情况下失效</b>
 * （{@code SPEC-admin-console §4.7} 验收清单）。</p>
 */
public final class Temperature {

    /**
     * 🔴 <b>绝对硬下界</b>（正常态最低，不可编辑）——「永不失去它」这条保证的载体。
     *
     * <p>⚠️ 🔴 <b>这不是免费用户的回落地板 {@code TEMP_FLOOR_FREE}</b>，
     * 尽管两者本期取值都是 60。实现回落时不得把 {@code TEMP_FLOOR_FREE} 做成它的别名，
     * 理由见类文档最后一节。</p>
     */
    public static final double FLOOR = 60.0;

    /** 温度天花板。 */
    public static final double CEILING = 100.0;

    /** 每次主人回访的回暖率（朝 100 靠拢的比例）。 */
    public static final double HEAL_RATE = 0.25;

    private Temperature() {
    }

    /** 初值 clamp（新建宠物默认 72）。 */
    public static double normalize(double t) {
        return clamp(t);
    }

    /**
     * 主人回访一次后的温度：朝天花板回暖，永不低于地板。
     *
     * @param current 当前温度
     * @return 回暖后的温度，clamp 到 [60,100]
     */
    public static double onOwnerVisit(double current) {
        double t = clamp(current);
        double next = t + HEAL_RATE * (CEILING - t);
        return clamp(next);
    }

    private static double clamp(double t) {
        if (t < FLOOR) {
            return FLOOR;
        }
        return Math.min(t, CEILING);
    }
}
