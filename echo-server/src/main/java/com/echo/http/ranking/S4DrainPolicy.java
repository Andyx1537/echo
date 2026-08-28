package com.echo.http.ranking;

import com.echo.http.governance.FeatureSwitchService;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code S4 自然流掉} 的准入闸：这套退场机制<b>现在能不能生效</b>。
 *
 * <p>{@code S4} 的退场条件是「近 14 天独立互动者 = 0 → 退出主动分发」。本类<b>不</b>负责
 * 数这个数（同 {@link SurgeModel}，计数由调用方按清洗口径先算好），只负责回答
 * 「这个 0 能不能拿来判流掉」。</p>
 *
 * <h2>🔴 关着，而且是永久关着——直到有人补一条裁定</h2>
 *
 * <p>{@code S4} 要的是<b>每张卡</b>近 14 天的独立互动者数。而 {@code DECISIONS RK-H}
 * （2026-08-26）已裁定 <b>互动本身不搬家</b>：卡级只加一列入口归因，🔴 <b>仅供归因、
 * 不参与任何计数</b>，且「独立互动者数一律仍从窗级取」。</p>
 *
 * <p>也就是说，{@code S4} 按字面要的那个数 🔴 <b>被裁定为不存在，而不是「还没实现」</b>。
 * 今天传进 {@link #decide(int)} 的永远是 0，而这个 0 的含义是「卡级从来不记计数」，
 * 不是「没人理这张卡」——⚠️ <b>这两件事在数据里长得一模一样</b>
 * （见 {@link InteractionScopeMigration}）。</p>
 *
 * <p>若此时放 {@code S4} 生效，上线满 14 天的那一刻<b>全库的卡会同时被判流掉</b>。
 * 更糟的是它<b>查不出来</b>：报表上退场率曲线是平滑的，没有异常尖峰，
 * 没有任何一条日志会说「因为卡级不记计数，所以我把所有卡都流掉了」，
 * 看起来就只是「内容陆续自然退场了」——而「自然退场」正是这个机制该有的样子。</p>
 *
 * <h2>🔴 2026-08-27 更正：理由换了，行为没换</h2>
 *
 * <p>本类原先把闸门挂在 {@link InteractionScopeMigration#isCardLevel()} 上，并写
 * 「迁移第③步落地后自己变可开」。<b>行为是对的（该关），理由是错的</b>：</p>
 *
 * <ul>
 *   <li>那个第③步<b>已被 {@code RK-H} 取消</b>，不会落地；</li>
 *   <li>于是「等迁移完成就自己开」这句话描述的是一件<b>不会发生的事</b>，
 *       实际效果是把 {@code S4} 永久关死，而代码读起来像是暂时关着；</li>
 *   <li>🔴 更实际的危害：{@code isCardLevel()} 恒假 ⇒ {@link #enabled()} 整个表达式恒假
 *       ⇒ <b>开关那一半从来没被求值过，断言它的用例什么都没验</b>（详见下一节）。</li>
 * </ul>
 *
 * <p>所以闸门改成 {@link #RULED_OPEN} 这个<b>显式开关</b>，并把真实理由写在它上面。
 * 关闭的<b>行为</b>与更正前完全一致。</p>
 *
 * <h2>要解除，需要的是一条裁定而不是一次实现</h2>
 *
 * <p>🔴 <b>{@code S4} 现在缺的不是代码，是「那 14 天的独立互动者到底数谁」这条口径。</b>
 * 在 {@code RK-H} 之下至少有两条出路，两条都得制作人拍：</p>
 *
 * <ol>
 *   <li><b>改成窗级粒度退场</b>——按窗的 14 天独立互动者判，一扇窗的卡一起退场。
 *       数据今天就有（{@code t_flower_log} / {@code t_remember} 记的就是 petId），
 *       但语义变了：⚠️ <b>退场单位从「一张卡」变成「一扇窗」</b>，
 *       新发的卡会被同窗旧卡的冷清连带拖走。</li>
 *   <li><b>放弃 {@code S4}</b>——承认在「互动不搬家」之下没有卡级冷启退场这个机制，
 *       退场交给别的信号（如曝光衰减）。</li>
 * </ol>
 *
 * <p>⚠️ <b>不要挑一条自己实现掉。</b>两条给出的产品行为不同，而 {@code S4} 一旦生效
 * 影响的是全库内容的分发寿命。裁定落定那天：改 {@link #RULED_OPEN} 为 {@code true}，
 * 并把 {@link #decide(int)} 的入参口径注释改写成拍板的那一条。</p>
 *
 * <h2>🔴 为什么要留 {@link #ruledOpen} 这个构造入口（假绿）</h2>
 *
 * <p>闸门写成常量时，{@link #enabled()} 的 {@code &&} 左边恒假，
 * <b>右边（运营开关）永远短路、从不求值</b>。后果是单测里所有形如
 * {@code assertThat(policy.enabled()).isFalse()} 的断言 🔴 <b>无论开关逻辑对不对都会通过</b>：
 * 哪怕「拒写」整个失效、哪怕 {@code setEnabled(false)} 反而把开关打开了，那些断言照样绿。</p>
 *
 * <p>这与增量编译拿旧产物当通过、未开 {@code -parameters} 让反射断言恒过是<b>同一类缺陷</b>：
 * <b>测试通过，但其实什么都没验</b>（见 {@code docs/BUILD-VERIFICATION.md}）。</p>
 *
 * <p>所以把闸门做成<b>实例字段</b>、留一个包内可见的构造器：生产路径读 {@link #RULED_OPEN}
 * （运营点不开它，安全性与原先一致），单测则可以构造一个「裁定已开」的实例，
 * 去真正验证开关那一半的行为。🔴 <b>这个构造器不对外公开，不是运营可配项。</b></p>
 */
@Slf4j
public final class S4DrainPolicy {

    /**
     * 🔴 <b>{@code S4} 的口径是否已被裁定、机制是否准予生效。</b>
     *
     * <p>{@code false} = 关闸。理由不是「功能没做完」，而是
     * <b>{@code RK-H} 裁定卡级不记互动计数，{@code S4} 按字面要的那个数不存在</b>；
     * 补齐它需要一条新裁定（窗级粒度退场 / 放弃 {@code S4}），见类文档。</p>
     *
     * <p>⚠️ <b>改成 {@code true} 之前必须先有那条裁定，并同步改写
     * {@link #decide(int)} 的入参口径说明。</b>单纯把它翻成 {@code true}，
     * 效果是全库的卡在满 14 天那天一起流掉，且报表上查不出来。</p>
     *
     * <p>写成常量而不是配置项：它记的是<b>「有没有那条裁定」</b>，
     * 不是运营的灰度选择。配置项可以在裁定还没有的时候被点开，常量不行。</p>
     */
    public static final boolean RULED_OPEN = false;

    /** 判定结论。{@code reason} 说明为什么留、为什么走，用于退场原因分布的埋点。 */
    public record Decision(boolean drain, Reason reason) {
    }

    /** 判定原因。 */
    public enum Reason {
        /** 近 14 天独立互动者为 0，按 {@code S4} 退出主动分发。 */
        DRAINED("drained"),
        /** 近 14 天仍有独立互动者，留在主动分发里。 */
        HAS_INTERACTORS("has_interactors"),
        /**
         * 🔴 {@code S4} 未生效：卡级不记互动计数（{@code RK-H}），传进来的数不可信。
         *
         * <p>这个原因出现在埋点里<b>不是异常</b>，是当前的正常状态。
         * 它消失的那天是「14 天独立互动者数谁」这条口径被拍板的那天，
         * ⚠️ <b>不是某次实现完成的那天</b>。</p>
         */
        SUPPRESSED_NO_CARD_LEVEL_COUNT("suppressed_no_card_level_count"),
        /** {@code S4} 开关被运营关闭（口径已裁定，但灰度阀门没开）。 */
        SUPPRESSED_SWITCH_OFF("suppressed_switch_off");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        /** 埋点字面量。 */
        public String code() {
            return code;
        }
    }

    private final FeatureSwitchService switches;

    /**
     * 本实例是否处于「口径已裁定」态。生产路径恒为 {@link #RULED_OPEN}；
     * 单测可经包内构造器给 {@code true}，以验证开关那一半的行为。
     */
    private final boolean ruledOpen;

    /** 生产构造器：闸门取 {@link #RULED_OPEN}，运营无法绕过。 */
    public S4DrainPolicy(FeatureSwitchService switches) {
        this(switches, RULED_OPEN);
    }

    /**
     * 🔴 <b>包内可见，仅供单测</b>：构造一个指定闸门状态的实例。
     *
     * <p>存在的唯一理由是让「开关控制 {@code S4}」这条行为可被真正断言——
     * 闸门恒假时那些断言恒过，等于没有测（见类文档最后一节）。
     * ⚠️ <b>不要在生产代码里调用它</b>，生产只允许走
     * {@link #S4DrainPolicy(FeatureSwitchService)}。</p>
     */
    S4DrainPolicy(FeatureSwitchService switches, boolean ruledOpen) {
        this.switches = switches;
        this.ruledOpen = ruledOpen;
    }

    /** {@code S4} 现在是否真的生效：口径已裁定<b>且</b>运营开关开着，缺一不可。 */
    public boolean enabled() {
        return ruledOpen && switches.isEnabled(FeatureSwitchService.KEY_S4_NATURAL_DRAIN);
    }

    /**
     * 判一张卡该不该自然流掉。
     *
     * <p>🔴 <b>任何 {@code S4} 的实现都必须走这个方法</b>，而不是自己去比一次
     * {@code interactors == 0}。绕过它就等于绕过了关闸，
     * 而绕过之后的失效不会以报错的形式出现。</p>
     *
     * @param distinctInteractors14d 近 14 天独立互动者数。⚠️ 🔴 <b>当前无可信口径</b>：
     *                               {@code RK-H} 定了卡级只记归因不记计数，所以传进来的
     *                               卡级数恒为 0 且不可信，本方法一律返回
     *                               {@link Reason#SUPPRESSED_NO_CARD_LEVEL_COUNT}。
     *                               口径拍板后（窗级粒度 / 或放弃 {@code S4}）
     *                               连同 {@link #RULED_OPEN} 一起改写这段说明
     */
    public Decision decide(int distinctInteractors14d) {
        if (!ruledOpen) {
            // 传进来的 0 不是「没人理」，是「卡级不记计数」——不判流掉
            return new Decision(false, Reason.SUPPRESSED_NO_CARD_LEVEL_COUNT);
        }
        if (!switches.isEnabled(FeatureSwitchService.KEY_S4_NATURAL_DRAIN)) {
            return new Decision(false, Reason.SUPPRESSED_SWITCH_OFF);
        }
        return distinctInteractors14d > 0
                ? new Decision(false, Reason.HAS_INTERACTORS)
                : new Decision(true, Reason.DRAINED);
    }

    /**
     * 启动期把当前状态喊出来一次。
     *
     * <p>⚠️ 🔴 <b>这条告警不再是「自熄」的</b>。更正前它挂在一个会自己变 true 的迁移判据上，
     * 所以敢写「没有人需要记得回来删它」；现在判据是一条<b>缺失的裁定</b>，
     * 而缺失的裁定不会自己补上——<b>这条告警会一直打印，直到有人拍板。</b>
     * 这是刻意的：它是那条裁定唯一的常驻提醒。</p>
     */
    public void logReadiness() {
        if (ruledOpen) {
            log.info("S4 自然流掉：口径已裁定，开关可开；当前开关 enabled={}",
                    switches.isEnabled(FeatureSwitchService.KEY_S4_NATURAL_DRAIN));
            return;
        }
        log.warn("""
                ================= 🔴 S4 自然流掉已关闸（缺一条裁定，刻意如此） =================
                RK-H 已定「互动本身不搬家」：卡级只记入口归因、不参与任何计数，
                独立互动者数一律从窗级取。于是 S4 按字面要的「每张卡近 14 天独立互动者」
                🔴 是一个被裁定为不存在的数，而不是一个还没实现的数。
                若此时放 S4 生效，上线满 14 天的那一刻全库的卡会同时被判流掉，
                而报表上它看起来只是「内容陆续自然退场了」——没有尖峰、没有异常、查不出来。
                🔴 开关 {} 在此期间写不进去（拒写，不是写进去再告警）。
                解除条件：🔴 需要一条新裁定回答「那 14 天的独立互动者到底数谁」——
                          ① 改成窗级粒度退场（数据今天就有，但退场单位从卡变成窗）；
                          ② 放弃 S4，退场交给曝光衰减等别的信号。
                ⚠️ 本条告警不会自熄：缺失的裁定不会自己补上，它是那条裁定唯一的常驻提醒。
                ==========================================================================""",
                FeatureSwitchService.KEY_S4_NATURAL_DRAIN);
    }
}
