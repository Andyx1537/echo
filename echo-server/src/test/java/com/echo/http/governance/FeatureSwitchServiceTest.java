package com.echo.http.governance;

import com.echo.http.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code S13} 留一句话开关的硬约束单测（{@code DECISIONS §G⁗⁗⁗‴ S13}）。
 *
 * <p>这里钉的是<b>「拒绝写入」而不是「允许打开 + 记告警」</b>。两者功能上都能"实现"裁定的文字，
 * 但告警式等于没有约束：告警会被当噪音划掉，而输入框已经对所有人开着了。</p>
 */
class FeatureSwitchServiceTest {

    private static final long OPERATOR = 1001L;
    private static final long APPROVER = 1002L;

    private CapabilityRegistry capabilities;
    private FeatureSwitchService switches;

    @BeforeEach
    void setUp() {
        capabilities = new CapabilityRegistry();
        switches = new FeatureSwitchService(new FeatureSwitchStore(null), capabilities);
    }

    private void makeAllReady() {
        for (GovernanceCapability c : GovernanceCapability.values()) {
            capabilities.register(c, () -> true);
        }
    }

    /** 🔴 P0 默认关闭。这是裁定的一部分，不是缺省值凑巧如此。 */
    @Test
    void defaultsToOffAtP0() {
        assertThat(switches.isLeaveWordsEnabled()).isFalse();
    }

    /** 🔴 未注册探针的能力一律视为未就绪，而不是「没说就算有」。 */
    @Test
    void unregisteredCapabilitiesAreNotReady() {
        assertThat(capabilities.allReady()).isFalse();
        assertThat(capabilities.notReady())
                .containsExactly(GovernanceCapability.values());
    }

    /**
     * 🔴 核心：治理能力未就绪时，开关<b>写不进去</b>——不是写进去再告警。
     *
     * <p>并且错误里必须带未就绪清单，否则运营不知道还差什么。</p>
     */
    @Test
    void cannotTurnOnWhileAnyCapabilityMissing() {
        // 只差文本安全闸一项
        capabilities.register(GovernanceCapability.BLOCK, () -> true);
        capabilities.register(GovernanceCapability.REPORT, () -> true);
        capabilities.register(GovernanceCapability.CLOSE_INTERACTION, () -> true);
        capabilities.register(GovernanceCapability.MODERATION_QUEUE, () -> true);
        capabilities.register(GovernanceCapability.TEXT_SAFETY_GATE, () -> false);

        assertThatThrownBy(() -> switches.setEnabled(
                FeatureSwitchService.KEY_LEAVE_WORDS, true, OPERATOR, APPROVER))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", FeatureSwitchService.ERR_CAPABILITY_NOT_READY)
                .hasMessageContaining("这个开关还不能打开");

        assertThat(switches.isLeaveWordsEnabled())
                .as("🔴 拒写：状态必须仍是关闭").isFalse();
    }

    /** 五项全就绪 + 二次审批 → 可以打开。 */
    @Test
    void turnsOnWhenAllReadyWithSecondApproval() {
        makeAllReady();
        switches.setEnabled(FeatureSwitchService.KEY_LEAVE_WORDS, true, OPERATOR, APPROVER);
        assertThat(switches.isLeaveWordsEnabled()).isTrue();
    }

    /**
     * {@code SPEC-admin-console §4.7 ②}：「内容可见性」类开关不看比例一律二次审批。
     * 🔴 自己批自己不算复核。
     */
    @Test
    void requiresSecondApprovalByDifferentPerson() {
        makeAllReady();

        assertThatThrownBy(() -> switches.setEnabled(
                FeatureSwitchService.KEY_LEAVE_WORDS, true, OPERATOR, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ApiException.BAD_PARAM);

        assertThatThrownBy(() -> switches.setEnabled(
                FeatureSwitchService.KEY_LEAVE_WORDS, true, OPERATOR, OPERATOR))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", FeatureSwitchService.ERR_SELF_APPROVAL);

        assertThat(switches.isLeaveWordsEnabled()).isFalse();
    }

    /**
     * ⚠️ 关闭方向<b>不设前置</b>：能力退化时必须能立刻关掉。
     *
     * <p>安全阀门只在「开」的方向上有阻力——如果关闭也要审批，那么发现问题时最该做的动作
     * 反而是最难做的。</p>
     */
    @Test
    void turningOffNeedsNoPrerequisite() {
        makeAllReady();
        switches.setEnabled(FeatureSwitchService.KEY_LEAVE_WORDS, true, OPERATOR, APPROVER);

        // 能力退化
        capabilities.register(GovernanceCapability.TEXT_SAFETY_GATE, () -> false);
        switches.setEnabled(FeatureSwitchService.KEY_LEAVE_WORDS, false, OPERATOR, null);
        assertThat(switches.isLeaveWordsEnabled()).isFalse();
    }

    /** 探针自己抛异常 → 按未就绪处理。宁可开关打不开，不可在状态不明时放开。 */
    @Test
    void throwingProbeCountsAsNotReady() {
        makeAllReady();
        capabilities.register(GovernanceCapability.REPORT, () -> {
            throw new IllegalStateException("probe boom");
        });
        assertThat(capabilities.isReady(GovernanceCapability.REPORT)).isFalse();
        assertThatThrownBy(() -> switches.setEnabled(
                FeatureSwitchService.KEY_LEAVE_WORDS, true, OPERATOR, APPROVER))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", FeatureSwitchService.ERR_CAPABILITY_NOT_READY);
    }

    /** 未知 key 一律按关闭处理（默认拒绝，不是默认放开）。 */
    @Test
    void unknownKeyIsOff() {
        assertThat(switches.isEnabled("no_such_switch")).isFalse();
    }
}
