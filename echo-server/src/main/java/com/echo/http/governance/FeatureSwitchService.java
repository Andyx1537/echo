package com.echo.http.governance;

import com.echo.http.ApiException;
import com.echo.http.ranking.S4DrainPolicy;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 功能开关（{@code DECISIONS §G⁗⁗⁗‴ S13}）。
 *
 * <p>两个开关，🔴 <b>都默认关闭</b>：</p>
 * <ul>
 *   <li>{@link #KEY_LEAVE_WORDS}（C1 留一句话）—— 前置是 {@code S13} 的五项治理能力；</li>
 *   <li>{@link #KEY_S4_NATURAL_DRAIN}（S4 自然流掉）—— 🔴 前置是<b>一条尚未做出的裁定</b>：
 *       「那 14 天的独立互动者，到底数谁」。
 *       ⚠️ <b>2026-08-27 更正</b>：这里原先写的是「前置是『献花与记得已迁到卡级』」，
 *       而按 {@code DECISIONS RK-H} <b>那次迁移不会发生</b>（互动不搬家）——
 *       🔴 <b>照原话写，运营看到的就是「还没迁完」，于是永远在等一个不会到来的状态。</b>
 *       详见 {@link com.echo.http.ranking.S4DrainPolicy}。</li>
 * </ul>
 *
 * <h2>为什么「拒绝写入」而不是「允许打开 + 记个告警」</h2>
 *
 * <p>{@code S13} 的原话是「开关只允许在文本安全闸与 {@code S3} 五项治理能力全部就绪后打开」。
 * 这句话有两种实现方式，差别很大：</p>
 *
 * <ul>
 *   <li><b>告警式</b>：允许置为开，同时记一条「治理能力未就绪」的告警。
 *       —— 这等于没有约束。告警会被当噪音划掉，而输入框已经对所有人开着了。</li>
 *   <li>🔴 <b>拒写式（本类的做法）</b>：任一项未就绪则 {@link #setEnabled} 直接失败并回未就绪清单。
 *       想打开就必须先把能力做出来，没有第二条路。</li>
 * </ul>
 *
 * <p>这条与 {@code MOD1 ②} 防的是同一类问题：<em>一个配置动作就能改变系统的风险面，
 * 而流水上看起来正常</em>。所以约束必须落在写入路径上，而不是落在事后观测上。</p>
 *
 * <p>⚠️ 关闭方向<b>不设前置</b>：能力退化时应当能立刻关掉。安全阀门只在「开」的方向上有阻力。</p>
 *
 * <h2>🔴 前置是<b>按开关分别登记</b>的</h2>
 *
 * <p>早先的实现把「{@code S13} 五项治理能力」当成了所有开关的通用前置。那只在当时成立——
 * 因为只有一个开关。{@code S4 自然流掉} 的前置与治理能力毫无关系，沿用会得到两个都错的结果：
 * 治理能力齐了就能打开一个不该打开的 {@code S4}，而治理能力缺一项又会挡住本可以打开的它。
 * 所以前置收进 {@link #PRECONDITIONS}，<b>每个开关各登记一条</b>。</p>
 *
 * <p>🔴 <b>未登记前置的 key 一律不允许打开</b>，而不是「没登记就等于无前置」。
 * 方向同 {@link CapabilityRegistry}：漏登记的后果是开关打不开（可发现、可修复），
 * 反过来则是新开关默认无人把守（不可发现）。</p>
 */
@Slf4j
public final class FeatureSwitchService {

    /** C1 留一句话。 */
    public static final String KEY_LEAVE_WORDS = "leave_words";

    /**
     * {@code S4 自然流掉}（近 14 天独立互动者 = 0 → 退出主动分发）。
     *
     * <p>🔴 <b>默认关闭且打不开</b>，理由与解除条件见
     * {@link com.echo.http.ranking.S4DrainPolicy}。一句话：{@code RK-H} 已定卡级不记互动计数，
     * {@code S4} 要的那个数<b>被裁定为不存在</b>（不是没实现），放它生效会让全库的卡在同一天
     * 一起流掉，而报表上看起来只是「内容自然退场了」。</p>
     */
    public static final String KEY_S4_NATURAL_DRAIN = "s4_natural_drain";

    /** 全部已登记的开关（供后台列表与测试遍历）。 */
    public static final List<String> KEYS = List.of(KEY_LEAVE_WORDS, KEY_S4_NATURAL_DRAIN);

    /** 3420：开启前置未满足，开关不可置为开。 */
    public static final int ERR_CAPABILITY_NOT_READY = 3420;
    /** 3421：二次审批人不能是发起人本人。 */
    public static final int ERR_SELF_APPROVAL = 3421;

    /**
     * 一个开关的开启前置。
     *
     * @param unmet 返回<b>还差什么</b>的清单；空表示可以开。🔴 返回清单而不是布尔，
     *              是因为运营看到「打不开」之后第一件事就是问「还差什么」——
     *              这个信息必须由前置本身给出，不能靠人去翻代码
     * @param hint  拒绝时给运营看的一句话（{@code §17.1} 允许后台回显技术细节，
     *              但这一句是给人读的，不是 detail）
     */
    private record Precondition(java.util.function.Function<CapabilityRegistry, List<String>> unmet,
                                String hint) {
    }

    /**
     * 每个开关各自的开启前置。🔴 新增开关必须在这里显式登记一条，未登记则打不开。
     *
     * <p>不要把这里合并成一条「通用前置」——两个开关的前置在语义上毫无交集，
     * 合并的那一刻就会出现「治理能力齐了所以 S4 可以开」这种既错又说得通的推理。</p>
     */
    private static final Map<String, Precondition> PRECONDITIONS = Map.of(
            // S13：文本安全闸 + S3 五项治理能力全部就绪
            KEY_LEAVE_WORDS, new Precondition(
                    caps -> caps.notReady().stream()
                            .map(c -> c.name() + "(" + c.displayName() + ")")
                            .toList(),
                    "这个开关还不能打开，先把治理能力补齐。"),
            // 🔴 缺一条裁定：RK-H 定了卡级不记互动计数，S4 要的那个数不存在。运营点不开它
            KEY_S4_NATURAL_DRAIN, new Precondition(
                    caps -> S4DrainPolicy.RULED_OPEN
                            ? List.of()
                            : List.of("s4_interactor_scope_ruling=missing"
                                    + "（RK-H 已定「互动本身不搬家」：卡级只记入口归因、不参与任何计数，"
                                    + "独立互动者数一律从窗级取，故卡级互动者数恒为 0 且不可信；"
                                    + "此时开启 S4 会让全库的卡在同一天一起流掉，且报表上看不出来。"
                                    + "解除需先裁定「那 14 天的独立互动者数谁」——改窗级粒度退场，或放弃 S4）"),
                    "这个开关还不能打开：S4 的独立互动者口径还没裁定（RK-H 已定卡级不记计数）。"));

    private final FeatureSwitchStore store;
    private final CapabilityRegistry capabilities;

    public FeatureSwitchService(FeatureSwitchStore store, CapabilityRegistry capabilities) {
        this.store = store;
        this.capabilities = capabilities;
    }

    /** 开关当前是否开启。读路径不做就绪校验（就绪是开启的前置，不是使用的前置）。 */
    public boolean isEnabled(String key) {
        return store.isEnabled(key);
    }

    /** {@code C1 留一句话} 是否开启。 */
    public boolean isLeaveWordsEnabled() {
        return isEnabled(KEY_LEAVE_WORDS);
    }

    /**
     * 该开关当前<b>还差什么</b>才能打开；空 = 可以打开。
     *
     * <p>供后台展示与 QA 直接断言。🔴 它读的是与 {@link #setEnabled} <b>同一份</b>前置，
     * 不是另抄一份判断——否则后台会显示「可以打开」而写入仍然被拒。</p>
     */
    public List<String> unmetPreconditions(String key) {
        Precondition p = PRECONDITIONS.get(key);
        if (p == null) {
            return List.of("unregistered_switch:" + key);
        }
        return p.unmet().apply(capabilities);
    }

    /**
     * 置开关状态。
     *
     * @param approvedBy 二次审批人（{@code SPEC-admin-console §4.7 ②}「内容可见性」类，
     *                   🔴 不看比例一律二次审批）；关闭方向可传 null
     * @throws ApiException 3420 开启前置未满足（开方向）；3421 自己批自己
     */
    public FeatureSwitchStore.State setEnabled(String key, boolean enabled,
                                               long operatorId, Long approvedBy) {
        if (enabled) {
            // 🔴 前置校验在写入之前，且不满足就是写不进去 —— 不是写进去再告警
            Precondition pre = PRECONDITIONS.get(key);
            if (pre == null) {
                // 未登记前置的开关一律打不开：漏登记要能被发现，而不是默认无人把守
                throw new ApiException(ERR_CAPABILITY_NOT_READY,
                        "这个开关还没有登记开启前置，不能打开。",
                        "no precondition registered for switch: " + key
                                + "; registered: " + PRECONDITIONS.keySet());
            }
            List<String> missing = pre.unmet().apply(capabilities);
            if (!missing.isEmpty()) {
                throw notReady(key, pre.hint(), missing);
            }
            if (approvedBy == null) {
                throw new ApiException(ApiException.BAD_PARAM,
                        "这个开关需要另一位同事复核后才能打开。",
                        "second approval required for switch: " + key);
            }
            if (approvedBy == operatorId) {
                throw new ApiException(ERR_SELF_APPROVAL,
                        "这个开关需要另一位同事复核后才能打开。",
                        "self-approval rejected: operator and approver are both " + operatorId);
            }
        }
        FeatureSwitchStore.State s = store.setEnabled(key, enabled, operatorId,
                enabled ? approvedBy : null, System.currentTimeMillis());
        log.info("功能开关变更 key={} enabled={} by={} approvedBy={}", key, enabled, operatorId, approvedBy);
        return s;
    }

    /** 当前就绪状况（供后台展示「还差哪几项」，以及 QA 直接断言）。 */
    public CapabilityRegistry capabilities() {
        return capabilities;
    }

    private static ApiException notReady(String key, String hint, List<String> missing) {
        // 后台可以回显技术细节（§17.1），运营需要知道还差哪几项才能补
        String detail = "precondition not met for " + key + ": " + String.join(", ", missing);
        return new ApiException(ERR_CAPABILITY_NOT_READY, hint, detail);
    }
}
