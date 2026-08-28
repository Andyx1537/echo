package com.echo.http.governance;

import com.echo.infra.persistence.PgDb;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code C1 留一句话}的文本存储（{@code t_resonance_text}）。无 DB 时退化为内存态，语义一致。
 *
 * <p>沿 {@link BlockStore} 的写法：一个类同时承载落库与内存两种模式，避免同一份语义写两遍。</p>
 *
 * <h2>🔴 不留物理删除入口</h2>
 *
 * <p>作者选「不留」记的是 {@code disposition='declined'}，<b>不删行</b>。删了之后
 * 「作者拒了」与「从来没人留过」在数据上不可区分，而这两者对
 * 「深共鸣率的分母该不该算这一次」是<b>相反</b>的答案。</p>
 *
 * <h2>🔴 一人对一张卡只留一句</h2>
 *
 * <p>由 {@code t_resonance_text_uk_card_actor} 唯一索引保证，不靠前端 disable。
 * 这是反骚扰的结构性约束：没有楼中楼、没有对话，所以留言不可能演变成纠缠。</p>
 */
@Slf4j
public final class LeaveWordsStore {

    /** 作者尚未处理。 */
    public static final String PENDING = "pending";
    /** 收下公开。 */
    public static final String PUBLIC = "public";
    /** 只自己看。 */
    public static final String PRIVATE = "private";
    /** 不留。🔴 用词照 {@code PALETTE §56} 原文，不是「拒绝」。 */
    public static final String DECLINED = "declined";

    /** 文本安全闸判定：待检 / 通过 / 命中。🔴 {@code REJECTED} 一律不进任何展示路径。 */
    public static final String SAFETY_PENDING = "pending";
    public static final String SAFETY_PASSED = "passed";
    public static final String SAFETY_REJECTED = "rejected";

    /** 一条留言。字段与 {@code t_resonance_text} 一一对应。 */
    public static final class Entry {
        public long id;
        public long cardId;
        public long ownerId;
        public long actorId;
        public String body = "";
        public String disposition = PENDING;
        public Long handledAt;
        public String safetyState = SAFETY_PENDING;
        public long createdAt;
    }

    private final PgDb db;
    /** id -> 留言。内存态用它当唯一真源。 */
    private final Map<Long, Entry> byId = new ConcurrentHashMap<>();
    /** "cardId:actorId" -> id。🔴 一人一卡一句的内存态等价物。 */
    private final Map<String, Long> uniqueIndex = new ConcurrentHashMap<>();

    public LeaveWordsStore(PgDb db) {
        this.db = db;
    }

    private static String uniqueKey(long cardId, long actorId) {
        return cardId + ":" + actorId;
    }

    /** 这个人在这张卡上已经留过的那一句；没留过返回 null。 */
    public Entry existing(long cardId, long actorId) {
        if (db == null) {
            Long id = uniqueIndex.get(uniqueKey(cardId, actorId));
            return id == null ? null : byId.get(id);
        }
        String sql = """
                SELECT "id","cardId","ownerId","actorId","body","disposition","handledAt",
                       "safetyState","createdAt"
                  FROM "t_resonance_text"
                 WHERE "cardId"=? AND "actorId"=? AND "deletedAt" IS NULL
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cardId);
            ps.setLong(2, actorId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("读 t_resonance_text 失败", e);
        }
    }

    public Entry byId(long id) {
        if (db == null) {
            return byId.get(id);
        }
        String sql = """
                SELECT "id","cardId","ownerId","actorId","body","disposition","handledAt",
                       "safetyState","createdAt"
                  FROM "t_resonance_text"
                 WHERE "id"=? AND "deletedAt" IS NULL
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("读 t_resonance_text 失败", e);
        }
    }

    /**
     * 落一条留言。
     *
     * @return false 表示这个人在这张卡上已经留过（唯一约束命中），本次<b>不覆盖</b>
     */
    public boolean insert(Entry e) {
        if (db == null) {
            Long prev = uniqueIndex.putIfAbsent(uniqueKey(e.cardId, e.actorId), e.id);
            if (prev != null) {
                return false;
            }
            byId.put(e.id, e);
            return true;
        }
        String sql = """
                INSERT INTO "t_resonance_text"
                       ("id","cardId","ownerId","actorId","body","disposition","safetyState","createdAt")
                VALUES (?,?,?,?,?,?,?,?)
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, e.id);
            ps.setLong(2, e.cardId);
            ps.setLong(3, e.ownerId);
            ps.setLong(4, e.actorId);
            ps.setString(5, e.body);
            ps.setString(6, e.disposition);
            ps.setString(7, e.safetyState);
            ps.setLong(8, e.createdAt);
            ps.executeUpdate();
            return true;
        } catch (SQLException dup) {
            // 唯一索引命中 = 已经留过。🔴 不当异常抛给调用方：那会变成一条可探测的信号
            log.debug("留言唯一约束命中，本次不落库 cardId={} actorId={}", e.cardId, e.actorId);
            return false;
        }
    }

    /**
     * 作者待处理队列：自己卡上 {@code pending} 且过了安全闸的留言，按时间升序（先来先处理）。
     *
     * <p>🔴 {@code safetyState='rejected'} 的<b>不出现在这里</b>。作者不该被要求去处理
     * 一条系统已经判定为违法违规的内容 —— 让它出现在队列里，等于把违规内容送到作者眼前，
     * 还要他点一下「不留」。</p>
     */
    public List<Entry> pendingOf(long ownerId, long cardId, int limit) {
        if (db == null) {
            List<Entry> out = new ArrayList<>();
            for (Entry e : byId.values()) {
                if (e.ownerId == ownerId && e.cardId == cardId
                        && PENDING.equals(e.disposition) && SAFETY_PASSED.equals(e.safetyState)) {
                    out.add(e);
                }
            }
            out.sort((a, b) -> Long.compare(a.createdAt, b.createdAt));
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        }
        String sql = """
                SELECT "id","cardId","ownerId","actorId","body","disposition","handledAt",
                       "safetyState","createdAt"
                  FROM "t_resonance_text"
                 WHERE "ownerId"=? AND "cardId"=? AND "disposition"='pending'
                   AND "safetyState"='passed' AND "deletedAt" IS NULL
                 ORDER BY "createdAt" ASC
                 LIMIT ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ownerId);
            ps.setLong(2, cardId);
            ps.setInt(3, limit);
            List<Entry> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("读 t_resonance_text 待处理队列失败", e);
        }
    }

    /**
     * 作者三选一处理。
     *
     * <p>🔴 带 {@code disposition='pending'} 条件：处理是<b>一次性</b>的，
     * 不是先查再写（那中间有竞态窗口，两个并发请求都能查到「还没处理」）。
     * 影响行数为 0 即说明已经处理过了。</p>
     *
     * @return false 表示这条已被处理过（或不存在），本次不生效
     */
    public boolean resolve(long id, long ownerId, String disposition, long now) {
        if (db == null) {
            Entry e = byId.get(id);
            if (e == null || e.ownerId != ownerId || !PENDING.equals(e.disposition)) {
                return false;
            }
            synchronized (e) {
                if (!PENDING.equals(e.disposition)) {
                    return false;
                }
                e.disposition = disposition;
                e.handledAt = now;
            }
            return true;
        }
        String sql = """
                UPDATE "t_resonance_text"
                   SET "disposition"=?, "handledAt"=?
                 WHERE "id"=? AND "ownerId"=? AND "disposition"='pending' AND "deletedAt" IS NULL
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, disposition);
            ps.setLong(2, now);
            ps.setLong(3, id);
            ps.setLong(4, ownerId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("更新 t_resonance_text 处置失败", e);
        }
    }

    /** 卡详情页展示用：已被作者收下公开、且过了安全闸的留言。 */
    public List<Entry> publicOf(long cardId, int limit) {
        if (db == null) {
            List<Entry> out = new ArrayList<>();
            for (Entry e : byId.values()) {
                if (e.cardId == cardId && PUBLIC.equals(e.disposition)
                        && SAFETY_PASSED.equals(e.safetyState)) {
                    out.add(e);
                }
            }
            out.sort((a, b) -> Long.compare(a.createdAt, b.createdAt));
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        }
        String sql = """
                SELECT "id","cardId","ownerId","actorId","body","disposition","handledAt",
                       "safetyState","createdAt"
                  FROM "t_resonance_text"
                 WHERE "cardId"=? AND "disposition"='public' AND "safetyState"='passed'
                   AND "deletedAt" IS NULL
                 ORDER BY "createdAt" ASC
                 LIMIT ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cardId);
            ps.setInt(2, limit);
            List<Entry> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("读 t_resonance_text 公开留言失败", e);
        }
    }

    private static Entry read(ResultSet rs) throws SQLException {
        Entry e = new Entry();
        e.id = rs.getLong("id");
        e.cardId = rs.getLong("cardId");
        e.ownerId = rs.getLong("ownerId");
        e.actorId = rs.getLong("actorId");
        e.body = rs.getString("body");
        e.disposition = rs.getString("disposition");
        long handled = rs.getLong("handledAt");
        e.handledAt = rs.wasNull() ? null : handled;
        e.safetyState = rs.getString("safetyState");
        e.createdAt = rs.getLong("createdAt");
        return e;
    }
}
