package com.echo.http.exposure;

import com.echo.infra.persistence.PgDb;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code reqId → 下发快照}（{@code TECH-DESIGN §3.10.4} 防刷校验 1–4 的依据）。
 *
 * <p>{@code GET /plaza} 每次下发时登记一条：这次发给了谁、发了哪些 id、各自的位次、
 * 通道/池归属、下发时刻。上报时按 {@code reqId} 反查，这样"伪造任意卡的曝光"就变得不可能——
 * 攻击者只能给"服务端确实发给他的卡"报曝光，而那本来就是要算的。</p>
 *
 * <p>有库时读写 {@code t_feed_request}，换实例仍能按同一份 {@code reqId} 校验。
 * 无库时仍是进程内 LRU；那时多副本会把合法上报判成 {@code unknown_req}，启动期拦下。</p>
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
    private final PgDb db;
    private final Map<String, Snapshot> snapshots;

    public FeedRequestRegistry(ExposureConfig config) {
        this(config, null);
    }

    public FeedRequestRegistry(ExposureConfig config, PgDb db) {
        this.config = config;
        this.db = db;
        int max = Math.max(1, config.reqMax());
        this.snapshots = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Snapshot> eldest) {
                return size() > max;
            }
        });
    }

    private boolean persistent() {
        return db != null;
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
        Snapshot snap = new Snapshot(viewerId, targetKind, surface, Map.copyOf(positions),
                Set.copyOf(boostIds == null ? Set.of() : boostIds),
                channel == null ? "" : channel, pool == null ? "" : pool,
                System.currentTimeMillis());
        persist(reqId, snap);
        snapshots.put(reqId, snap);
        return reqId;
    }

    /** @return 该 reqId 的快照；不存在或已过 TTL 返回 null。 */
    public Snapshot lookup(String reqId) {
        if (reqId == null || reqId.isBlank()) {
            return null;
        }
        Snapshot s = snapshots.get(reqId);
        if (s == null) {
            s = load(reqId);
            if (s != null) {
                snapshots.put(reqId, s);
            }
        }
        if (s == null) {
            return null;
        }
        if (isExpired(s)) {
            snapshots.remove(reqId);
            delete(reqId);
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
        int memory = before - snapshots.size();
        if (!persistent()) {
            return memory;
        }
        long cutoff = System.currentTimeMillis() - config.reqTtlSeconds() * 1000L;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM \"t_feed_request\" WHERE \"deliveredAt\" < ?")) {
            ps.setLong(1, cutoff);
            return memory + ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("清理过期 feed 快照失败: {}", e.getMessage());
            return memory;
        }
    }

    // ------------------------------------------------ 单实例前提的可发现性

    /**
     * 声明副本数的环境变量。无库部署必须单副本；有库时快照已共享，此变量只作观测。
     */
    public static final String ENV_REPLICA_COUNT = "ECHO_REPLICA_COUNT";

    /** 显式跳过检查（明知风险仍要多实例跑，例如灰度演练）。 */
    public static final String ENV_ALLOW_MULTI = "ECHO_ALLOW_MULTI_INSTANCE_EXPOSURE";

    /**
     * 启动期断言：无库时仍是单进程快照，声明多副本则拒绝启动。
     * 有库时快照在 {@code t_feed_request}，多副本可以跑。
     */
    public static void assertSingleInstance() {
        assertSingleInstance(false);
    }

    public static void assertSingleInstance(boolean sharedSnapshots) {
        if (sharedSnapshots) {
            return;
        }
        int replicas = replicaCount();
        if (replicas <= 1) {
            return;
        }
        if (Boolean.parseBoolean(System.getenv(ENV_ALLOW_MULTI))) {
            log.error("🔴 {}={} 但已显式放行（{}=true）：无库时曝光上报会因 reqId 跨实例不可见而被大面积"
                            + "判为 unknown_req，t_card_exposure 将系统性偏低。仅限灰度演练。",
                    ENV_REPLICA_COUNT, replicas, ENV_ALLOW_MULTI);
            return;
        }
        throw new IllegalStateException(String.format(
                "曝光记账（FeedRequestRegistry）无库时是单进程内存态，不支持多实例：%s=%d。"
                        + "reqId 快照无法跨实例读取，上报会被判 unknown_req 并静默丢弃。"
                        + "请打开 PostgreSQL 让快照落 t_feed_request，或改为单副本；"
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
     * 健康检查字段。无库时 {@code singleInstanceAssumed=true}；有库时快照可跨实例。
     */
    public Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("singleInstanceAssumed", !persistent());
        h.put("snapshotStore", persistent() ? "postgres" : "memory");
        h.put("declaredReplicas", replicaCount());
        h.put("snapshots", size());
        h.put("snapshotCapacity", config.reqMax());
        h.put("ttlSeconds", config.reqTtlSeconds());
        return h;
    }

    private void persist(String reqId, Snapshot snap) {
        if (!persistent()) {
            return;
        }
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO \"t_feed_request\""
                             + " (\"reqId\",\"viewerId\",\"targetKind\",\"surface\","
                             + "\"deliveredPositions\",\"boostIds\",\"channel\",\"pool\",\"deliveredAt\")"
                             + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, reqId);
            ps.setLong(2, snap.viewerId());
            ps.setString(3, snap.targetKind());
            ps.setString(4, snap.surface());
            ps.setString(5, encodePositions(snap.deliveredPositions()));
            ps.setString(6, encodeBoosts(snap.boostIds()));
            ps.setString(7, snap.channel());
            ps.setString(8, snap.pool());
            ps.setLong(9, snap.deliveredAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("写入 feed 快照失败 reqId={}: {}", reqId, e.getMessage());
        }
    }

    private Snapshot load(String reqId) {
        if (!persistent()) {
            return null;
        }
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT \"viewerId\",\"targetKind\",\"surface\",\"deliveredPositions\","
                             + "\"boostIds\",\"channel\",\"pool\",\"deliveredAt\""
                             + " FROM \"t_feed_request\" WHERE \"reqId\"=?")) {
            ps.setString(1, reqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Snapshot(
                        rs.getLong("viewerId"),
                        rs.getString("targetKind"),
                        rs.getString("surface"),
                        decodePositions(rs.getString("deliveredPositions")),
                        decodeBoosts(rs.getString("boostIds")),
                        rs.getString("channel"),
                        rs.getString("pool"),
                        rs.getLong("deliveredAt"));
            }
        } catch (SQLException e) {
            log.warn("读取 feed 快照失败 reqId={}: {}", reqId, e.getMessage());
            return null;
        }
    }

    private void delete(String reqId) {
        if (!persistent()) {
            return;
        }
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM \"t_feed_request\" WHERE \"reqId\"=?")) {
            ps.setString(1, reqId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("删除过期 feed 快照失败 reqId={}: {}", reqId, e.getMessage());
        }
    }

    static String encodePositions(Map<String, Integer> positions) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> e : positions.entrySet()) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(e.getKey()).append(':').append(e.getValue());
        }
        return out.toString();
    }

    static Map<String, Integer> decodePositions(String raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        for (String part : raw.split(",")) {
            int colon = part.lastIndexOf(':');
            if (colon <= 0 || colon == part.length() - 1) {
                continue;
            }
            try {
                out.put(part.substring(0, colon), Integer.parseInt(part.substring(colon + 1)));
            } catch (NumberFormatException ignored) {
                // 脏行跳过，整份快照仍可用其余位次
            }
        }
        return Map.copyOf(out);
    }

    static String encodeBoosts(Set<String> boostIds) {
        if (boostIds == null || boostIds.isEmpty()) {
            return "";
        }
        return String.join(",", boostIds);
    }

    static Set<String> decodeBoosts(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return Set.copyOf(out);
    }
}
