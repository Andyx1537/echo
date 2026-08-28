package com.echo.http.store;

import com.aengine.util.id.IDGenerator;
import com.echo.http.model.ModerationModels.CardStatus;
import com.echo.http.model.ModerationModels.CardVisibilityLog;
import com.echo.http.model.ModerationModels.MemoryCard;

import java.util.List;

/** 内存态卡存储：置顶上限、自动解除、可见性 CAS 与流水。 */
public final class StoreProbe {

    static int failures = 0;
    static long seq = 1;

    public static void main(String[] args) throws Exception {
        IDGenerator ids = new IDGenerator(1L);
        InMemoryModerationStore store = new InMemoryModerationStore(ids);

        long owner = 42L;
        MemoryCard c1 = put(store, owner, 1001, CardStatus.PUBLIC, "public", 1000L, 10L);
        MemoryCard c2 = put(store, owner, 1002, CardStatus.PUBLIC, "public", 1000L, 20L);
        MemoryCard c3 = put(store, owner, 1003, CardStatus.PUBLIC, "public", 1000L, 30L);
        MemoryCard c4 = put(store, owner, 1004, CardStatus.PUBLIC, "public", 1000L, 40L);
        MemoryCard draft = put(store, owner, 1005, CardStatus.DRAFT, "private", null, null);

        section("广场候选");
        List<MemoryCard> pub = store.publicCards(50);
        eq("只出 status=public 的卡（草稿不出）", 4, pub.size());
        eq("按 publishedAt DESC", 1004L, pub.get(0).id);

        section("作者卡列表");
        eq("作者能看到草稿（含全部状态）", 5, store.cardsOfOwner(owner, 50).size());
        eq("别人的 ownerId 查不到", 0, store.cardsOfOwner(99L, 50).size());

        section("置顶上限 3");
        eq("置顶第 1 张", ModerationStore.PinOutcome.OK,
                store.setPinnedAtomically(1001, owner, true, 3, 100L));
        eq("置顶第 2 张", ModerationStore.PinOutcome.OK,
                store.setPinnedAtomically(1002, owner, true, 3, 101L));
        eq("置顶第 3 张", ModerationStore.PinOutcome.OK,
                store.setPinnedAtomically(1003, owner, true, 3, 102L));
        eq("已置顶数 = 3", 3, store.countPinned(owner));
        eq("🔴 第 4 张必须被上限挡住", ModerationStore.PinOutcome.AT_CAPACITY,
                store.setPinnedAtomically(1004, owner, true, 3, 103L));
        check("🔴 被挡住时没有写进去", store.card(1004).pinnedAt == null);
        eq("重复置顶已置顶的卡幂等、不占额度", ModerationStore.PinOutcome.OK,
                store.setPinnedAtomically(1001, owner, true, 3, 104L));
        eq("幂等后计数不变", 3, store.countPinned(owner));

        section("不满足条件不可置顶");
        eq("🔴 草稿不可置顶", ModerationStore.PinOutcome.NOT_FOUND,
                store.setPinnedAtomically(1005, owner, true, 3, 105L));
        eq("🔴 不是自己的卡回 NOT_FOUND（不区分归属与不存在）",
                ModerationStore.PinOutcome.NOT_FOUND,
                store.setPinnedAtomically(1001, 99L, true, 3, 106L));

        section("取消置顶");
        eq("取消置顶", ModerationStore.PinOutcome.OK,
                store.setPinnedAtomically(1003, owner, false, 3, 107L));
        eq("计数降到 2", 2, store.countPinned(owner));
        eq("取消后腾出额度，第 4 张能置顶了", ModerationStore.PinOutcome.OK,
                store.setPinnedAtomically(1004, owner, true, 3, 108L));

        section("作者改可见性 + 流水 + 自动解除置顶");
        check("1001 当前是置顶的", store.card(1001).pinnedAt != null);
        check("改成 friends 且 clearPin",
                store.changeVisibilityAtomically(1001, owner, "public", "friends", true, 200L));
        eq("可见性已改", "friends", store.card(1001).visibilityIntent);
        check("🔴 置顶被同一次操作解除，不留悬空", store.card(1001).pinnedAt == null);

        List<CardVisibilityLog> logs = store.historyOfCard(1001);
        eq("落了一条流水", 1, logs.size());
        eq("流水 from", "public", logs.get(0).fromVisibility);
        eq("流水 to", "friends", logs.get(0).toVisibility);
        eq("🔴 changedRole=author（白名单本来就有这一档）", "author", logs.get(0).changedRole);
        eq("changedBy 是作者", owner, logs.get(0).changedBy);
        eq("作者改可见性不改状态：流水两端状态同值",
                logs.get(0).fromStatus, logs.get(0).toStatus);

        section("可见性 CAS");
        check("🔴 期望值不对时拒绝（另一个请求抢先改过）",
                !store.changeVisibilityAtomically(1001, owner, "public", "private", false, 201L));
        eq("CAS 未命中时值没变", "friends", store.card(1001).visibilityIntent);
        check("🔴 不是自己的卡改不动",
                !store.changeVisibilityAtomically(1002, 99L, "public", "private", false, 202L));

        section("并发置顶：上限必须守住");
        InMemoryModerationStore cs = new InMemoryModerationStore(new IDGenerator(2L));
        long o2 = 7L;
        for (int i = 0; i < 20; i++) {
            put(cs, o2, 2000 + i, CardStatus.PUBLIC, "public", 1L, (long) i);
        }
        Thread[] ts = new Thread[20];
        for (int i = 0; i < 20; i++) {
            final long id = 2000 + i;
            ts[i] = new Thread(() -> cs.setPinnedAtomically(id, o2, true, 3, 300L));
        }
        for (Thread t : ts) {
            t.start();
        }
        for (Thread t : ts) {
            t.join();
        }
        eq("🔴 20 个线程同时置顶，最终只有 3 张（先查后写会变成 20）", 3, cs.countPinned(o2));

        System.out.println(failures == 0
                ? "\n=== 全部通过 ==="
                : "\n=== 🔴 " + failures + " 条失败 ===");
        if (failures > 0) {
            System.exit(1);
        }
    }

    static MemoryCard put(InMemoryModerationStore store, long owner, long id, String status,
                          String visibility, Long reviewedAt, Long publishedAt) {
        MemoryCard c = new MemoryCard();
        c.id = id;
        c.ownerId = owner;
        c.petId = 1L;
        c.status = status;
        c.visibilityIntent = visibility;
        c.reviewedAt = reviewedAt;
        c.publishedAt = publishedAt;
        c.originType = "user";
        c.title = "";
        c.body = "正文。";
        c.coverKey = "";
        c.sourceType = "record";
        store.putCard(c);
        return c;
    }

    static void section(String name) {
        System.out.println("\n---- " + name + " ----");
    }

    static void eq(String what, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        System.out.println((ok ? "ok   - " : "FAIL - ") + what
                + (ok ? "" : "  [期望 " + expected + " 实际 " + actual + "]"));
        if (!ok) {
            failures++;
        }
    }

    static void eq(String what, long expected, long actual) {
        eq(what, (Object) Long.valueOf(expected), (Object) Long.valueOf(actual));
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   - " : "FAIL - ") + what);
        if (!ok) {
            failures++;
        }
    }
}
