package com.echo.http.governance;

import com.echo.infra.persistence.PgDb;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拉黑关系存储（{@code t_block}）。单向：{@code accountId} 拉黑 {@code peerId}。
 *
 * <p>无 DB 时退化为内存态，语义一致。</p>
 *
 * <p>🔴 <b>热点是反向查询</b>：每一次互动都要问「内容主人有没有拉黑我」，即已知发起人反查
 * 拉黑方。所以内存态同时维护正向与反向两个索引，落库态靠 {@code t_block_idx_peer}。</p>
 */
@Slf4j
public final class BlockStore {

    private final PgDb db;
    /** accountId -> 他拉黑了谁。 */
    private final Map<Long, Set<Long>> blocking = new ConcurrentHashMap<>();
    /** peerId -> 谁拉黑了他。🔴 拦截路径读这一个。 */
    private final Map<Long, Set<Long>> blockedBy = new ConcurrentHashMap<>();

    public BlockStore(PgDb db) {
        this.db = db;
        if (db != null) {
            load();
        }
    }

    private void load() {
        String sql = "SELECT \"accountId\",\"peerId\" FROM \"t_block\"";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                index(rs.getLong(1), rs.getLong(2));
            }
        } catch (SQLException e) {
            // 🔴 读不到不能"当作没有拉黑"继续跑：那会让所有拉黑静默失效，
            //    而失效方向恰好是把骚扰放回来。宁可启动失败。
            throw new IllegalStateException("读取 t_block 失败，拒绝以「无拉黑」状态启动", e);
        }
    }

    private void index(long accountId, long peerId) {
        blocking.computeIfAbsent(accountId, k -> ConcurrentHashMap.newKeySet()).add(peerId);
        blockedBy.computeIfAbsent(peerId, k -> ConcurrentHashMap.newKeySet()).add(accountId);
    }

    private void deindex(long accountId, long peerId) {
        Set<Long> f = blocking.get(accountId);
        if (f != null) {
            f.remove(peerId);
        }
        Set<Long> r = blockedBy.get(peerId);
        if (r != null) {
            r.remove(accountId);
        }
    }

    /**
     * {@code accountId} 是否拉黑了 {@code peerId}。
     *
     * <p>🔴 拦截路径调用点：{@code isBlocked(内容主人, 互动发起人)}。</p>
     */
    public boolean isBlocked(long accountId, long peerId) {
        Set<Long> s = blocking.get(accountId);
        return s != null && s.contains(peerId);
    }

    /** 两人之间是否存在任一方向的拉黑（用于双向断流的可见性判断）。 */
    public boolean eitherDirection(long a, long b) {
        return isBlocked(a, b) || isBlocked(b, a);
    }

    /** 谁拉黑了我（拦截路径的反向索引）。 */
    public Set<Long> blockersOf(long peerId) {
        Set<Long> s = blockedBy.get(peerId);
        return s == null ? Set.of() : Collections.unmodifiableSet(s);
    }

    /** 我拉黑了谁。 */
    public List<Long> blockedList(long accountId) {
        Set<Long> s = blocking.get(accountId);
        return s == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(s));
    }

    /**
     * 建立拉黑。幂等：已存在则不产生第二行、也不刷新时间。
     *
     * @return true 表示本次真的新建了（供审计与"是否需要连带解关注"判断）
     */
    public boolean block(long id, long accountId, long peerId, long now) {
        if (isBlocked(accountId, peerId)) {
            return false;
        }
        if (db != null) {
            String sql = """
                    INSERT INTO "t_block" ("id","accountId","peerId","createdAt") VALUES (?,?,?,?)
                    ON CONFLICT ("accountId","peerId") DO NOTHING
                    """;
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.setLong(2, accountId);
                ps.setLong(3, peerId);
                ps.setLong(4, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("写 t_block 失败", e);
            }
        }
        index(accountId, peerId);
        return true;
    }

    /**
     * 解除拉黑。
     *
     * <p>物理删除而非软删：解除之后「我曾经拉黑过谁」对系统没有任何用处，
     * 而它是 PIPL 下的行为个人信息。解除动作本身在 {@code t_audit_log} 里有痕。</p>
     *
     * @return true 表示本次真的删掉了一行
     */
    public boolean unblock(long accountId, long peerId) {
        if (!isBlocked(accountId, peerId)) {
            return false;
        }
        if (db != null) {
            String sql = "DELETE FROM \"t_block\" WHERE \"accountId\"=? AND \"peerId\"=?";
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, accountId);
                ps.setLong(2, peerId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("删 t_block 失败", e);
            }
        }
        deindex(accountId, peerId);
        return true;
    }
}
