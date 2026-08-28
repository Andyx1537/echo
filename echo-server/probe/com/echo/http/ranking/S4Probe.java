package com.echo.http.ranking;

import com.echo.http.governance.CapabilityRegistry;
import com.echo.http.governance.FeatureSwitchService;
import com.echo.http.governance.FeatureSwitchStore;
import com.echo.http.governance.GovernanceCapability;

/** S4 关闸行为的真断言，javac+java 直跑，不走 Maven。 */
public final class S4Probe {

    static int failures = 0;

    public static void main(String[] args) {
        CapabilityRegistry caps = new CapabilityRegistry();
        for (GovernanceCapability c : GovernanceCapability.values()) {
            caps.register(c, () -> true);
        }
        FeatureSwitchStore store = new FeatureSwitchStore(null);
        FeatureSwitchService switches = new FeatureSwitchService(store, caps);

        // ---- 生产实例：闸门关着 ----
        S4DrainPolicy prod = new S4DrainPolicy(switches);
        check("生产闸门必须关着", !S4DrainPolicy.RULED_OPEN);
        check("生产 enabled() 必须 false", !prod.enabled());
        check("interactors=0 不判流掉", !prod.decide(0).drain());
        check("原因是「卡级不记计数」",
                prod.decide(0).reason() == S4DrainPolicy.Reason.SUPPRESSED_NO_CARD_LEVEL_COUNT);
        check("🔴 关闸优先于计数：传 999 也是同一个原因",
                prod.decide(999).reason() == S4DrainPolicy.Reason.SUPPRESSED_NO_CARD_LEVEL_COUNT);

        // ---- 拒写：值不许落进存储 ----
        boolean threw = false;
        try {
            switches.setEnabled(FeatureSwitchService.KEY_S4_NATURAL_DRAIN, true, 2001L, 2002L);
        } catch (RuntimeException e) {
            threw = true;
        }
        check("开关必须拒写（抛异常）", threw);
        check("🔴 拒写后值没有落进存储",
                !store.isEnabled(FeatureSwitchService.KEY_S4_NATURAL_DRAIN));
        check("未满足前置要说清还差什么",
                switches.unmetPreconditions(FeatureSwitchService.KEY_S4_NATURAL_DRAIN).size() == 1
                        && switches.unmetPreconditions(FeatureSwitchService.KEY_S4_NATURAL_DRAIN)
                                .get(0).contains("s4_interactor_scope_ruling=missing"));

        // ---- 🔴 之前从没被覆盖过的那一半：闸门开了之后开关是否真的管事 ----
        S4DrainPolicy ruledSwitchOff = new S4DrainPolicy(switches, true);
        check("口径已裁定但开关关着 → 不生效", !ruledSwitchOff.enabled());
        check("原因必须是「开关关」而不是「没口径」",
                ruledSwitchOff.decide(0).reason() == S4DrainPolicy.Reason.SUPPRESSED_SWITCH_OFF);

        FeatureSwitchStore onStore = new FeatureSwitchStore(null);
        onStore.setEnabled(FeatureSwitchService.KEY_S4_NATURAL_DRAIN, true, 1L, 2L, 12345L);
        S4DrainPolicy on = new S4DrainPolicy(new FeatureSwitchService(onStore, caps), true);
        check("口径已裁定 + 开关已开 → 生效", on.enabled());
        check("interactors=0 → 流掉", on.decide(0).drain());
        check("流掉原因 = DRAINED", on.decide(0).reason() == S4DrainPolicy.Reason.DRAINED);
        check("interactors=1 → 留下", !on.decide(1).drain());
        check("留下原因 = HAS_INTERACTORS",
                on.decide(1).reason() == S4DrainPolicy.Reason.HAS_INTERACTORS);

        // ---- 迁移常量已是终态 ----
        check("isCardLevel() 恒假", !InteractionScopeMigration.isCardLevel());
        check("卡级互动者数不可信", !InteractionScopeMigration.cardInteractorCountIsTrustworthy());
        check("CURRENT 是 WINDOW（终态）",
                InteractionScopeMigration.WINDOW.equals(InteractionScopeMigration.CURRENT));

        System.out.println(failures == 0
                ? "\n=== 全部通过 ==="
                : "\n=== 🔴 " + failures + " 条失败 ===");
        if (failures > 0) {
            System.exit(1);
        }
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   - " : "FAIL - ") + what);
        if (!ok) {
            failures++;
        }
    }
}
