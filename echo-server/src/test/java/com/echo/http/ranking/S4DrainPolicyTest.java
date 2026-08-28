package com.echo.http.ranking;

import com.echo.http.ApiException;
import com.echo.http.governance.CapabilityRegistry;
import com.echo.http.governance.FeatureSwitchService;
import com.echo.http.governance.FeatureSwitchStore;
import com.echo.http.governance.GovernanceCapability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code S4 自然流掉} 关闸的硬约束单测。
 *
 * <p>钉的是三件事：默认关闭、<b>打不开</b>（拒写而非告警）、以及关着的时候
 * {@code interactors == 0} 也<b>不判流掉</b>。</p>
 *
 * <h2>🔴 2026-08-27：本文件修掉了一处「假绿」</h2>
 *
 * <p>更正前闸门取 {@link InteractionScopeMigration#isCardLevel()}，它<b>恒假</b>，于是
 * {@link S4DrainPolicy#enabled()} 的 {@code &&} 右边（运营开关）永远短路、从不求值。
 * 后果是本文件里所有 {@code assertThat(policy.enabled()).isFalse()} 🔴 <b>无论开关逻辑
 * 对不对都会通过</b>——包括
 * {@link #cannotBeTurnedOnWhileRulingIsMissing} 里那句标着「🔴 拒写：状态必须仍是关闭」的，
 * 哪怕拒写整个失效、值真被写进去了，它照样绿。</p>
 *
 * <p>⚠️ <b>那不是「测试写得不细」，是那几行断言一行都没验。</b>与增量编译拿旧产物当通过、
 * 未开 {@code -parameters} 让反射断言恒过属同一类缺陷，见 {@code docs/BUILD-VERIFICATION.md}。</p>
 *
 * <p>修法：{@link #switchActuallyGatesDrainOnceRulingLands} 用包内构造器造一个
 * <b>「口径已裁定」</b>的实例，在那条路径上开关不再被短路，
 * 于是「开关真的控制 {@code S4}」这条行为第一次被真正断言到。</p>
 */
class S4DrainPolicyTest {

    private static final long OPERATOR = 2001L;
    private static final long APPROVER = 2002L;

    private CapabilityRegistry capabilities;
    private FeatureSwitchService switches;
    private S4DrainPolicy policy;

    @BeforeEach
    void setUp() {
        capabilities = new CapabilityRegistry();
        switches = new FeatureSwitchService(new FeatureSwitchStore(null), capabilities);
        policy = new S4DrainPolicy(switches);
        // 治理能力全就绪：证明 S4 打不开与治理能力无关，是另一条前置
        for (GovernanceCapability c : GovernanceCapability.values()) {
            capabilities.register(c, () -> true);
        }
    }

    /** 🔴 默认关闭。这是裁定的一部分，不是缺省值凑巧如此。 */
    @Test
    void defaultsToOff() {
        assertThat(switches.isEnabled(FeatureSwitchService.KEY_S4_NATURAL_DRAIN)).isFalse();
        assertThat(policy.enabled()).isFalse();
    }

    /**
     * 🔴 闸门的理由必须是「缺裁定」，不是「等迁移」。
     *
     * <p>钉这一条是因为更正前的代码把闸门挂在一个<b>已被 {@code RK-H} 取消</b>的迁移上，
     * 读起来像「暂时关着，迁移完成就自己开」。⚠️ 行为一样，但下一个人会去等一件不会发生的事，
     * 而不是去补那条真正缺的裁定。</p>
     */
    @Test
    void gateIsAMissingRulingNotAPendingMigration() {
        assertThat(S4DrainPolicy.RULED_OPEN)
                .as("🔴 S4 口径尚未裁定，闸门必须是关的").isFalse();

        assertThat(InteractionScopeMigration.isCardLevel())
                .as("RK-H 已定互动本身不搬家，卡级口径永远不会到来").isFalse();
        assertThat(InteractionScopeMigration.CURRENT)
                .as("🔴 WINDOW 是终态，不是过渡态；改它需要显式改写 D8")
                .isEqualTo(InteractionScopeMigration.WINDOW);
    }

    /**
     * 🔴 核心：口径没裁定时开关<b>写不进去</b>——不是写进去再告警。
     *
     * <p>并且错误 detail 里要说清还差什么，否则运营只知道打不开、不知道为什么。</p>
     */
    @Test
    void cannotBeTurnedOnWhileRulingIsMissing() {
        assertThatThrownBy(() -> switches.setEnabled(
                FeatureSwitchService.KEY_S4_NATURAL_DRAIN, true, OPERATOR, APPROVER))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", FeatureSwitchService.ERR_CAPABILITY_NOT_READY)
                .hasMessageContaining("独立互动者口径还没裁定");

        // 🔴 直接断存储层：policy.enabled() 在闸门关着时恒假，断它等于什么都没断
        assertThat(switches.isEnabled(FeatureSwitchService.KEY_S4_NATURAL_DRAIN))
                .as("🔴 拒写：值必须没有落进存储").isFalse();
    }

    /** 打不开的原因要能被后台读到，且与写入路径读同一份前置。 */
    @Test
    void unmetPreconditionExplainsWhy() {
        assertThat(switches.unmetPreconditions(FeatureSwitchService.KEY_S4_NATURAL_DRAIN))
                .singleElement().asString()
                .contains("s4_interactor_scope_ruling=missing")
                .contains("卡级只记入口归因、不参与任何计数");

        // 治理能力全就绪 → leave_words 没有未满足前置。两个开关的前置互不相干
        assertThat(switches.unmetPreconditions(FeatureSwitchService.KEY_LEAVE_WORDS)).isEmpty();
    }

    /**
     * 🔴 最要紧的一条：关闸期间 {@code interactors == 0} <b>不判流掉</b>。
     *
     * <p>这个 0 的含义是「卡级不记互动计数」，不是「没人理这张卡」。
     * 若这里返回 drain=true，全库的卡会在满 14 天那天一起退场。</p>
     */
    @Test
    void zeroInteractorsDoesNotDrainWhileGateIsClosed() {
        S4DrainPolicy.Decision d = policy.decide(0);

        assertThat(d.drain()).isFalse();
        assertThat(d.reason())
                .isEqualTo(S4DrainPolicy.Reason.SUPPRESSED_NO_CARD_LEVEL_COUNT);

        // 闸门关着时入参完全不影响结论——传什么都一样
        assertThat(policy.decide(999).reason())
                .as("🔴 关闸优先于计数，不是「有人互动所以留下」")
                .isEqualTo(S4DrainPolicy.Reason.SUPPRESSED_NO_CARD_LEVEL_COUNT);
    }

    /**
     * 🔴 修掉假绿的那一条：<b>裁定落地之后，开关是否真的在控制 {@code S4}</b>。
     *
     * <p>生产实例的闸门恒关，{@code &&} 右边永远短路，所以这段逻辑在本文件里
     * 一直没有被任何用例覆盖过。这里用包内构造器造一个「口径已裁定」的实例，
     * 让开关那一半第一次真的被求值。</p>
     *
     * <p>⚠️ <b>本用例不改变生产行为</b>：{@link S4DrainPolicy#RULED_OPEN} 仍是
     * {@code false}，由 {@link #gateIsAMissingRulingNotAPendingMigration} 守着。</p>
     */
    @Test
    void switchActuallyGatesDrainOnceRulingLands() {
        S4DrainPolicy ruled = new S4DrainPolicy(switches, true);

        // 开关还关着：不流掉，且原因是「开关关」而不是「没口径」
        assertThat(ruled.enabled()).isFalse();
        assertThat(ruled.decide(0).reason())
                .isEqualTo(S4DrainPolicy.Reason.SUPPRESSED_SWITCH_OFF);

        // 把开关打开（绕过 setEnabled 的前置——这里要测的是 policy，不是前置）
        FeatureSwitchStore store = new FeatureSwitchStore(null);
        store.setEnabled(FeatureSwitchService.KEY_S4_NATURAL_DRAIN, true,
                OPERATOR, APPROVER, System.currentTimeMillis());
        S4DrainPolicy onAndRuled = new S4DrainPolicy(
                new FeatureSwitchService(store, capabilities), true);

        assertThat(onAndRuled.enabled()).as("🔴 口径已裁定 + 开关已开 → S4 生效").isTrue();
        assertThat(onAndRuled.decide(0))
                .isEqualTo(new S4DrainPolicy.Decision(true, S4DrainPolicy.Reason.DRAINED));
        assertThat(onAndRuled.decide(1))
                .isEqualTo(new S4DrainPolicy.Decision(false, S4DrainPolicy.Reason.HAS_INTERACTORS));
    }

    /** 未登记前置的 key 一律打不开，而不是「没登记就等于无前置」。 */
    @Test
    void unregisteredSwitchCannotBeTurnedOn() {
        assertThatThrownBy(() -> switches.setEnabled("no_such_switch", true, OPERATOR, APPROVER))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", FeatureSwitchService.ERR_CAPABILITY_NOT_READY);
    }

    /** 关闭方向不设前置：任何时候都能立刻关掉。 */
    @Test
    void turningOffNeedsNoPrerequisite() {
        switches.setEnabled(FeatureSwitchService.KEY_S4_NATURAL_DRAIN, false, OPERATOR, null);
        // 断存储层而不是 policy.enabled()：后者在闸门关着时恒假
        assertThat(switches.isEnabled(FeatureSwitchService.KEY_S4_NATURAL_DRAIN)).isFalse();
    }
}
