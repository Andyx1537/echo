package com.echo.http.exposure;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code reqId → 下发快照} 的内存 LRU（{@code TECH-DESIGN §3.10.4} 防刷校验 1–4 的依据）。
 *
 * <p>{@code GET /plaza} 每次下发时登记一条：这次发给了谁、发了哪些 id、各自的位次、
 * 通道/池归属、下发时刻。上报时按 {@code reqId} 反查，这样"伪造任意卡的曝光"就变得不可能——
 * 攻击者只能给"服务端确实发给他的卡"报曝光，而那本来就是要算的。</p>
 *
 * <h2>🔴 单实例前提（部署约束，不是实现细节）</h2>
 *
 * <p>本类是<b>单进程内存态</b>。多实例部署下 {@code reqId} 必须能被处理该请求的任意实例读到，
 * 否则会出现「在 A 实例拿到的 feed、上报打到 B 实例被判 {@code unknown_req}」——
 * 曝光被大面积静默丢弃，而 {@link ExposureRecorder.Reject#UNKNOWN_REQ} 在日志里看起来
 * 和"有人在伪造 reqId"一模一样。</p>
 *
 * <p>🔴 <b>这个失效必须在部署前被发现，不能靠上线后排查曝光数据异常。</b>因此：</p>
 * <ul>
 *   <li>{@link #assertSingleInstance} 在启动期检查多实例信号，命中则<b>拒绝启动</b>；</li>
 *   <li>{@link #health} 供健康检查暴露 {@code singleInstanceAssumed} 与
 *       {@code unknownReqRate}，运行期也能看出来。</li>
 * </ul>
 *
 * <p>改造方案与工作量估计见交付说明；当前是单实例，不值得为此引入 Redis。</p>
 */
@Slf4j
public final class FeedRequestRegistry {

    /** 下发的是回忆卡（{@code t_memory_card.id}）—— 唯一能记进 {@code t_card_exposure} 的口径。 */
    public static final String KIND_CARD = "card";
    /**
     * 下发的是宠物窗口（{@code petId}，API-CONTRACT §6 的广场）。
     *
     * <p>🔴 {@code t_card_exposure.cardId} 指向 {@code t_memory_card.id}，而 petId 是另一套 id。
     * 现阶段 {@code GET /plaza} 出的是窗口而不是回忆卡（回忆卡 feed 尚未实现），若照记会往权重衰减
     * 模型的数据源里灌一批 join 不上任何卡的行 —— 而且报表上看不出来。所以这类快照的曝光一律拒收。</p>
     */
    public static final String KIND_WINDOW = "window";

    /**
     * 🔴 <b>网格层（瀑布发现层）</b>——{@code GET /plaza}。
     *
     * <p>🔴 <b>这一层的曝光不记 {@code n}。</b>{@code SPEC-feed-surfaces} 概述第 1 条：
     * 记 {@code n} 的唯一条件是「全屏层 + 视口内连续驻留 ≥1000ms」，
     * <b>网格里被列出、被滚过一律不计</b>。</p>
     *
     * <p>理由：网格里停留 1000ms 是滑动时顺带发生的，不是「看了」。若网格也记，
     * 用户滑一屏就给十几张卡各记一次，⚠️ <b>一张卡单次浏览就能烧掉几十次额度</b>，
     * 而制动表三档是按<b>真实投放次数</b>标定的。</p>
     *
     * <p>🔴 <b>失真的表现方式是最坏的一种</b>：它表现为「内容衰减得比预期快」
     * （{@code n} 虚高一倍相当于把卡凭空催老几十小时），<b>而这在报表上查不出原因</b>——
     * 看板只会显示「内容平均寿命短」，没有任何指标会指向「曝光口径混了」。</p>
     */
    public static final String SURFACE_GRID = "grid";

    /**
     * <b>全屏单卡层</b>——一屏一条，🔴 <b>{@code n} 只在这一层记</b>。
     *
     * <p>⚠️ <b>该层目前尚未实现</b>（没有任何端点会用这个值登记快照）。
     * 常量先立在这里，是为了让「哪一层记 {@code n}」这个判据有<b>单一来源</b>，
     * 而不是等实现那天再临时决定。</p>
     */
    public static final String SURFACE_IMMERSIVE = "immersive";

    /** 一次 feed 下发的快照。 */
    public record Snapshot(
            long viewerId,
            /** 下发对象的口径，见 {@link #KIND_CARD} / {@link #KIND_WINDOW}。 */
            String targetKind,
            /**
             * 🔴 下发面，见 {@link #SURFACE_GRID} / {@link #SURFACE_IMMERSIVE}。
             *
             * <p><b>与 {@link #targetKind} 是两个正交的判据，不要合并</b>：前者问「发的是卡还是窗」，
             * 后者问「发在哪一层」。⚠️ 只判 {@code targetKind} 的话，广场改发回忆卡的那一刻
             * 网格曝光就会开始记 {@code n}，而那正是上面那条「查不出原因」的失真。</p>
             */
            String surface,
            /* 下发的 id → 位次（0-based）。成员检查用它，位次也从这里取（不信前端传的 pos）。 */
            Map<String, Integer> deliveredPositions,
            /* 占用了冷启动保底位的 id（服务端归属信息，前端既不知道也不该知道）。 */
            Set<String> boostIds,
            String channel,
            String pool,
            long deliveredAt) {

        public boolean contains(String id) {
            return deliveredPositions.containsKey(id);
        }

        /** 🔴 本次下发的曝光是否该记进 {@code n}：必须是<b>卡</b>且发在<b>全屏层</b>。 */
        public boolean countsTowardExposure() {
            return KIND_CARD.equals(targetKind) && SURFACE_IMMERSIVE.equals(surface);
        }
    }

    private final ExposureConfig config;
    private final Map<String, Snapshot> snapshots;

    public FeedRequestRegistry(ExposureConfig config) {
        this.config = config;
        int max = Math.max(1, config.reqMax());
        this.snapshots = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Snapshot> eldest) {
                return size() > max;
            }
        });
    }

    /**
     * 登记一次下发，返回新的 {@code reqId}（由 {@code GET /plaza} 响应下发给前端）。
     *
     * @param targetKind   {@link #KIND_CARD} 或 {@link #KIND_WINDOW}
     * @param surface      🔴 {@link #SURFACE_GRID} 或 {@link #SURFACE_IMMERSIVE}——
     *                     决定这批曝光算不算 {@code n}，<b>必须显式传</b>
     * @param deliveredIds 本次下发的 id，顺序即位次
     */
    public String register(long viewerId, String targetKind, String surface,
                           List<String> deliveredIds,
                           Set<String> boostIds, String channel, String pool) {
        String reqId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Integer> positions = new LinkedHashMap<>();
        for (int i = 0; i < deliveredIds.size(); i++) {
            positions.putIfAbsent(deliveredIds.get(i), i);
        }
        snapshots.put(reqId, new Snapshot(viewerId, targetKind, surface, Map.copyOf(positions),
                Set.copyOf(boostIds == null ? Set.of() : boostIds),
                channel == null ? "" : channel, pool == null ? "" : pool,
                System.currentTimeMillis()));
        return reqId;
    }

    /** @return 该 reqId 的快照；不存在或已过 TTL 返回 null。 */
    public Snapshot lookup(String reqId) {
        if (reqId == null || reqId.isBlank()) {
            return null;
        }
        Snapshot s = snapshots.get(reqId);
        if (s == null) {
            return null;
        }
        if (isExpired(s)) {
            snapshots.remove(reqId);
            return null;
        }
        return s;
    }

    private boolean isExpired(Snapshot s) {
        return System.currentTimeMillis() - s.deliveredAt() > config.reqTtlSeconds() * 1000L;
    }

    /** 当前快照条数（供 size 埋点：这是本方案新增的最大一块内存，约 30 MB @ 5 万条）。 */
    public int size() {
        return snapshots.size();
    }

    /** 清掉已过 TTL 的条目（定时任务调用；LRU 本身只在超容量时淘汰）。 */
    public int evictExpired() {
        int before = snapshots.size();
        synchronized (snapshots) {
            snapshots.entrySet().removeIf(e -> isExpired(e.getValue()));
        }
        return before - snapshots.size();
    }

    // ------------------------------------------------ 单实例前提的可发现性

    /**
     * 声明副本数的环境变量。部署方（K8s Deployment / compose scale）应当把它透传进来。
     *
     * <p>🔴 <b>为什么读环境变量而不是自己探测集群</b>：探测需要服务发现或共享存储，
     * 而我们恰恰是因为「不想为这件事引入 Redis」才停在单实例。读一个由部署方声明的数字，
     * 成本是零，且它<b>正好在扩副本的那次改动里被改到</b>——那是最需要被提醒的时刻。</p>
     */
    public static final String ENV_REPLICA_COUNT = "ECHO_REPLICA_COUNT";

    /** 显式跳过检查（明知风险仍要多实例跑，例如灰度演练）。 */
    public static final String ENV_ALLOW_MULTI = "ECHO_ALLOW_MULTI_INSTANCE_EXPOSURE";

    /**
     * 启动期断言单实例前提。
     *
     * <p>🔴 多副本时<b>抛异常拒绝启动</b>，而不是打个告警继续跑。理由：告警会被当噪音划掉，
     * 而这个失效的表现是「曝光数据静默偏低」——它不会让任何请求失败，只会让权重衰减模型
     * 长期读到错的数，等到有人发现排序不对劲，已经过去很久了。启动失败是刺眼的，
     * 而刺眼正是这里需要的。</p>
     *
     * @throws IllegalStateException 声明的副本数 &gt; 1 且未显式放行
     */
    public static void assertSingleInstance() {
        int replicas = replicaCount();
        if (replicas <= 1) {
            return;
        }
        if (Boolean.parseBoolean(System.getenv(ENV_ALLOW_MULTI))) {
            log.error("🔴 {}={} 但已显式放行（{}=true）：曝光上报会因 reqId 跨实例不可见而被大面积"
                            + "判为 unknown_req，t_card_exposure 将系统性偏低。仅限灰度演练。",
                    ENV_REPLICA_COUNT, replicas, ENV_ALLOW_MULTI);
            return;
        }
        throw new IllegalStateException(String.format(
                "曝光记账（FeedRequestRegistry）目前是单进程内存态，不支持多实例：%s=%d。"
                        + "reqId 快照无法跨实例读取，上报会被判 unknown_req 并静默丢弃，"
                        + "导致权重衰减模型读到系统性偏低的曝光数。"
                        + "请改为单副本，或先把快照迁到共享缓存（Redis）后再扩副本；"
                        + "确需带此风险运行请设 %s=true。",
                ENV_REPLICA_COUNT, replicas, ENV_ALLOW_MULTI));
    }

    private static int replicaCount() {
        String raw = System.getenv(ENV_REPLICA_COUNT);
        if (raw == null || raw.isBlank()) {
            return 1; // 未声明按单实例处理（现状）
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("{} 不是数字（{}），按单实例处理", ENV_REPLICA_COUNT, raw);
            return 1;
        }
    }

    /**
     * 健康检查字段。
     *
     * <p>把 {@code singleInstanceAssumed} 暴露出来，是为了让运行期也能看出这个前提——
     * 启动期断言只在启动那一刻有效，而副本数可能在之后被改。</p>
     */
    public Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("singleInstanceAssumed", true);
        h.put("declaredReplicas", replicaCount());
        h.put("snapshots", size());
        h.put("snapshotCapacity", config.reqMax());
        h.put("ttlSeconds", config.reqTtlSeconds());
        return h;
    }
}
