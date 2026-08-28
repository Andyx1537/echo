package com.echo.http.exposure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 曝光上报五道防刷校验的单测（{@code TECH-DESIGN-feed-recall-and-exposure §3.10.4}）。
 *
 * <p>客户端上报天然可伪造，所以这五道全在服务端。测的重点不是"正常路径能记账"，而是
 * <b>攻击者能做到的上限被压到 0</b>：他只能给"服务端确实发给他的卡"报曝光，而那本来就要算，
 * 且重复报会被 24h 去重吃掉。</p>
 *
 * <p>不接 DB（{@code db=null}），只验校验与去重逻辑；落库路径的正确性由
 * {@code t_card_exposure_uk_card_viewer_day} 唯一键 + {@code ON CONFLICT DO NOTHING} 承担。</p>
 */
class ExposureRecorderTest {

    private static final long VIEWER = 5001L;
    private static final long ATTACKER = 5002L;

    private ExposureConfig config;
    private FeedRequestRegistry registry;
    private ExposureRecorder recorder;

    @BeforeEach
    void setUp() {
        config = ExposureConfig.forTest();
        registry = new FeedRequestRegistry(config);
        recorder = new ExposureRecorder(config, registry, null, new com.aengine.util.id.IDGenerator(1L));
    }

    private String deliver(long viewerId, String... cardIds) {
        return registry.register(viewerId, FeedRequestRegistry.KIND_CARD, FeedRequestRegistry.SURFACE_IMMERSIVE, List.of(cardIds),
                java.util.Set.of(), "recent", "warm");
    }

    private ExposureRecorder.Item item(String cardId, int pos) {
        return new ExposureRecorder.Item(cardId, pos, 3000L, System.currentTimeMillis());
    }

    @Test
    void happyPathAccepts() {
        String reqId = deliver(VIEWER, "1001", "1002");
        ExposureRecorder.Outcome out = recorder.record(reqId, VIEWER,
                List.of(item("1001", 0), item("1002", 1)));
        assertThat(out.accepted()).isEqualTo(2);
        assertThat(out.rejected()).isZero();
        assertThat(recorder.pendingCount()).isEqualTo(2);
    }

    /** 校验 1：reqId 必须是服务端真实下发过的 → 整批丢弃。 */
    @Test
    void unknownReqIdDropsWholeBatch() {
        ExposureRecorder.Outcome out = recorder.record("forged-req-id", VIEWER,
                List.of(item("1001", 0), item("1002", 1)));
        assertThat(out.accepted()).isZero();
        assertThat(out.rejected()).isEqualTo(2);
        assertThat(recorder.pendingCount()).isZero();
    }

    /** 校验 2：viewerId 与下发对象不一致 → 整批丢弃（这是越权信号）。 */
    @Test
    void viewerMismatchDropsWholeBatch() {
        String reqId = deliver(VIEWER, "1001");
        ExposureRecorder.Outcome out = recorder.record(reqId, ATTACKER, List.of(item("1001", 0)));
        assertThat(out.accepted()).isZero();
        assertThat(out.rejected()).isEqualTo(1);
    }

    /**
     * 校验 3（整个防刷设计的支点）：cardId 必须在该 reqId 实际下发的集合里。
     *
     * <p>有了它，"伪造任意卡的曝光"就不可能了——攻击者只能给服务端确实发给他的卡报曝光。</p>
     */
    @Test
    void cardNotDeliveredIsDroppedPerItem() {
        String reqId = deliver(VIEWER, "1001");
        ExposureRecorder.Outcome out = recorder.record(reqId, VIEWER,
                List.of(item("1001", 0), item("9999", 1))); // 9999 没发给他
        assertThat(out.accepted()).as("下发过的那条正常记账").isEqualTo(1);
        assertThat(out.rejected()).as("🔴 未下发的卡必须被丢").isEqualTo(1);
    }

    /** 校验 4：ts 超出「下发时刻 ~ +30 分钟」的窗口 → 该条丢弃。 */
    @Test
    void outOfWindowTimestampIsDropped() {
        String reqId = deliver(VIEWER, "1001", "1002");
        long now = System.currentTimeMillis();
        ExposureRecorder.Outcome out = recorder.record(reqId, VIEWER, List.of(
                new ExposureRecorder.Item("1001", 0, 3000L, now - 60_000L),          // 早于下发
                new ExposureRecorder.Item("1002", 1, 3000L, now + 31 * 60_000L)));   // 超 30 分钟
        assertThat(out.accepted()).isZero();
        assertThat(out.rejected()).isEqualTo(2);
    }

    /** 校验 4：dwellMs 必须落在 [1000, 300000] → 否则该条丢弃。 */
    @Test
    void implausibleDwellIsDropped() {
        String reqId = deliver(VIEWER, "1001", "1002", "1003");
        long now = System.currentTimeMillis();
        ExposureRecorder.Outcome out = recorder.record(reqId, VIEWER, List.of(
                new ExposureRecorder.Item("1001", 0, 999L, now),        // 太短
                new ExposureRecorder.Item("1002", 1, 300_001L, now),    // 太长
                new ExposureRecorder.Item("1003", 2, 1000L, now)));     // 边界内
        assertThat(out.accepted()).isEqualTo(1);
        assertThat(out.rejected()).isEqualTo(2);
    }

    /** 校验 5：单 viewer 每分钟条数上限，超出部分丢弃（不是整批丢）。 */
    @Test
    void rateLimitDropsOverflowOnly() {
        ExposureConfig tight = new ExposureConfig(true, 1800, 1000, 3, 50, 1000, 100, 50);
        FeedRequestRegistry reg = new FeedRequestRegistry(tight);
        ExposureRecorder rec = new ExposureRecorder(tight, reg, null,
                new com.aengine.util.id.IDGenerator(1L));

        String reqId = reg.register(VIEWER, FeedRequestRegistry.KIND_CARD, FeedRequestRegistry.SURFACE_IMMERSIVE,
                List.of("1", "2", "3", "4", "5"), java.util.Set.of(), "recent", "warm");
        long now = System.currentTimeMillis();
        ExposureRecorder.Outcome out = rec.record(reqId, VIEWER, List.of(
                new ExposureRecorder.Item("1", 0, 2000L, now),
                new ExposureRecorder.Item("2", 1, 2000L, now),
                new ExposureRecorder.Item("3", 2, 2000L, now),
                new ExposureRecorder.Item("4", 3, 2000L, now),
                new ExposureRecorder.Item("5", 4, 2000L, now)));
        assertThat(out.accepted()).isEqualTo(3);
        assertThat(out.rejected()).isEqualTo(2);
    }

    /**
     * 24h 去重：同一 {@code (cardId, viewerId, day)} 重复上报只记一次额度。
     *
     * <p>重复上报<b>不算 rejected</b> —— 对前端来说这是成功的（已经记过账了），回 rejected
     * 会让前端以为要重试。这也是"攻击者多报几次"收益为 0 的地方。</p>
     */
    @Test
    void duplicateWithin24hConsumesQuotaOnce() {
        String reqId = deliver(VIEWER, "1001");
        recorder.record(reqId, VIEWER, List.of(item("1001", 0)));
        assertThat(recorder.pendingCount()).isEqualTo(1);

        ExposureRecorder.Outcome again = recorder.record(reqId, VIEWER, List.of(item("1001", 0)));
        assertThat(again.accepted()).isEqualTo(1);
        assertThat(again.rejected()).isZero();
        assertThat(recorder.pendingCount()).as("🔴 重复上报不得二次消耗额度").isEqualTo(1);
    }

    /** 🔴 前端传的归属信息一律忽略：viaBoost/channel/pool 只从服务端快照取。 */
    @Test
    void attributionComesFromServerSnapshotOnly() {
        String reqId = registry.register(VIEWER, FeedRequestRegistry.KIND_CARD, FeedRequestRegistry.SURFACE_IMMERSIVE,
                List.of("1001", "1002"), java.util.Set.of("1002"), "surge", "cold");
        recorder.record(reqId, VIEWER, List.of(item("1001", 0), item("1002", 1)));

        FeedRequestRegistry.Snapshot snap = registry.lookup(reqId);
        assertThat(snap.channel()).isEqualTo("surge");
        assertThat(snap.pool()).isEqualTo("cold");
        assertThat(snap.boostIds()).containsExactly("1002");
        // 位次同样以服务端快照为准，不信前端传的 pos
        assertThat(snap.deliveredPositions()).containsEntry("1001", 0).containsEntry("1002", 1);
    }

    /** 单请求上限：超出 batchMax 的部分直接丢。 */
    @Test
    void oversizedBatchIsTruncated() {
        ExposureConfig tight = new ExposureConfig(true, 1800, 1000, 200, 2, 1000, 100, 50);
        FeedRequestRegistry reg = new FeedRequestRegistry(tight);
        ExposureRecorder rec = new ExposureRecorder(tight, reg, null,
                new com.aengine.util.id.IDGenerator(1L));
        String reqId = reg.register(VIEWER, FeedRequestRegistry.KIND_CARD, FeedRequestRegistry.SURFACE_IMMERSIVE, List.of("1", "2", "3"),
                java.util.Set.of(), "recent", "warm");
        long now = System.currentTimeMillis();
        ExposureRecorder.Outcome out = rec.record(reqId, VIEWER, List.of(
                new ExposureRecorder.Item("1", 0, 2000L, now),
                new ExposureRecorder.Item("2", 1, 2000L, now),
                new ExposureRecorder.Item("3", 2, 2000L, now)));
        assertThat(out.accepted()).isEqualTo(2);
        assertThat(out.rejected()).isEqualTo(1);
    }

    /** reqId 过 TTL 后按 unknown_req 处理（内存 LRU 的固有行为，前端会看到 rejected）。 */
    @Test
    void expiredReqIdBehavesAsUnknown() throws Exception {
        ExposureConfig shortTtl = new ExposureConfig(true, 0, 1000, 200, 50, 1000, 100, 50);
        FeedRequestRegistry reg = new FeedRequestRegistry(shortTtl);
        ExposureRecorder rec = new ExposureRecorder(shortTtl, reg, null,
                new com.aengine.util.id.IDGenerator(1L));
        String reqId = reg.register(VIEWER, FeedRequestRegistry.KIND_CARD, FeedRequestRegistry.SURFACE_IMMERSIVE, List.of("1001"),
                java.util.Set.of(), "recent", "warm");

        Thread.sleep(2); // TTL=0，需越过登记时刻才算过期
        assertThat(reg.lookup(reqId)).isNull();
        ExposureRecorder.Outcome out = rec.record(reqId, VIEWER, List.of(item("1001", 0)));
        assertThat(out.accepted()).isZero();
        assertThat(out.rejected()).isEqualTo(1);
    }

    /**
     * 🔴 下发口径不是回忆卡时整批拒收。
     *
     * <p>{@code t_card_exposure.cardId} 指向 {@code t_memory_card.id}，而 petId 是另一套 id。
     * 照记会往权重衰减模型的数据源里灌一批 join 不上任何卡的行，而这种失效在报表上看不出来
     * —— 宁可一条不记。</p>
     *
     * <p>📌 {@code GET /plaza} 已于 2026-08-27 改发回忆卡，所以这条用例现在守的是
     * <b>别的下发路径不许拿窗 id 记曝光</b>，不再是描述广场的现状。</p>
     */
    @Test
    void windowFeedExposureIsRejectedWholesale() {
        String reqId = registry.register(VIEWER, FeedRequestRegistry.KIND_WINDOW, FeedRequestRegistry.SURFACE_GRID,
                List.of("1001", "1002"), java.util.Set.of(), "", "");
        ExposureRecorder.Outcome out = recorder.record(reqId, VIEWER,
                List.of(item("1001", 0), item("1002", 1)));
        assertThat(out.accepted()).isZero();
        assertThat(out.rejected()).isEqualTo(2);
        assertThat(recorder.pendingCount())
                .as("🔴 petId 一条都不许进 t_card_exposure").isZero();
    }

    /**
     * 🔴 <b>网格层（瀑布）的曝光整批拒收</b>——{@code n} 只在全屏层记。
     *
     * <p>{@code SPEC-feed-surfaces} 概述第 1 条：记 {@code n} 的唯一条件是「全屏层 +
     * 视口内连续驻留 ≥1000ms」，<b>网格里被列出、被滚过一律不计</b>。
     * 网格里停留 1000ms 是滑动时顺带发生的，不是「看了」。</p>
     *
     * <p>⚠️ 🔴 <b>这一条守的是「广场改发回忆卡」那一步的连带风险</b>：口径从
     * {@code window} 改成 {@code card} 之后，若只判 {@code targetKind}，网格曝光就会开始
     * 记 {@code n}——一个用户滑一屏给十几张卡各记一次，<b>一张卡单次浏览烧掉几十次额度</b>，
     * 而制动表三档是按真实投放次数标定的。失真表现为「内容衰减得比预期快」，
     * <b>报表上查不出原因</b>。</p>
     */
    @Test
    void gridSurfaceExposureIsRejectedWholesale() {
        String reqId = registry.register(VIEWER, FeedRequestRegistry.KIND_CARD,
                FeedRequestRegistry.SURFACE_GRID,
                List.of("1001", "1002"), java.util.Set.of(), "recent", "warm");
        ExposureRecorder.Outcome out = recorder.record(reqId, VIEWER,
                List.of(item("1001", 0), item("1002", 1)));

        assertThat(out.accepted())
                .as("🔴 网格层一条都不许进 t_card_exposure").isZero();
        assertThat(out.rejected()).isEqualTo(2);
        assertThat(recorder.pendingCount()).isZero();
    }

    /** 全屏层才记 n：同一批数据换成 immersive 就该被接受（证明上一条拒的是「面」不是别的）。 */
    @Test
    void immersiveSurfaceExposureIsAccepted() {
        String reqId = registry.register(VIEWER, FeedRequestRegistry.KIND_CARD,
                FeedRequestRegistry.SURFACE_IMMERSIVE,
                List.of("1001", "1002"), java.util.Set.of(), "recent", "warm");
        ExposureRecorder.Outcome out = recorder.record(reqId, VIEWER,
                List.of(item("1001", 0), item("1002", 1)));

        // 🔴 断 accepted > 0，不是只断 rejected == 0：整批拒收路径下 rejected 也可能被
        //    算成 items.size()，而「什么都没发生」与「全被拒」在只看 rejected 时不可区分。
        assertThat(out.accepted()).as("🔴 全屏层必须真的记进去").isEqualTo(2);
        assertThat(out.rejected()).isZero();
    }

    /** 快照自己就能回答「这批算不算 n」，判据不散落在调用方。 */
    @Test
    void snapshotKnowsWhetherItCounts() {
        String grid = registry.register(VIEWER, FeedRequestRegistry.KIND_CARD,
                FeedRequestRegistry.SURFACE_GRID, List.of("1"), java.util.Set.of(), "", "");
        String immersive = registry.register(VIEWER, FeedRequestRegistry.KIND_CARD,
                FeedRequestRegistry.SURFACE_IMMERSIVE, List.of("1"), java.util.Set.of(), "", "");
        String windowFeed = registry.register(VIEWER, FeedRequestRegistry.KIND_WINDOW,
                FeedRequestRegistry.SURFACE_IMMERSIVE, List.of("1"), java.util.Set.of(), "", "");

        assertThat(registry.lookup(grid).countsTowardExposure())
                .as("卡 + 网格 → 不记").isFalse();
        assertThat(registry.lookup(immersive).countsTowardExposure())
                .as("卡 + 全屏 → 记").isTrue();
        assertThat(registry.lookup(windowFeed).countsTowardExposure())
                .as("窗 + 全屏 → 不记（两个判据正交，缺一不可）").isFalse();
    }

    /** 总开关关闭时整体不记账（排序层按"额度无限"运行，仅联调）。 */
    @Test
    void disabledConfigRecordsNothing() {
        ExposureConfig off = new ExposureConfig(false, 1800, 1000, 200, 50, 1000, 100, 50);
        FeedRequestRegistry reg = new FeedRequestRegistry(off);
        ExposureRecorder rec = new ExposureRecorder(off, reg, null,
                new com.aengine.util.id.IDGenerator(1L));
        String reqId = reg.register(VIEWER, FeedRequestRegistry.KIND_CARD, FeedRequestRegistry.SURFACE_IMMERSIVE, List.of("1001"),
                java.util.Set.of(), "recent", "warm");
        assertThat(rec.record(reqId, VIEWER, List.of(item("1001", 0))).accepted()).isZero();
        assertThat(rec.pendingCount()).isZero();
    }
}
