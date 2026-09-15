package com.echo.http.work;

import com.echo.infra.persistence.PgDb;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 收藏是收藏者私有事实。公开 DTO 不得带收藏数。有库时读写 {@code t_work_favorite}。 */
@Slf4j
public final class WorkFavoriteStore {
    private final PgDb db;
    private final Map<String, Long> memory = new ConcurrentHashMap<>();

    public WorkFavoriteStore(PgDb db) {
        this.db = db;
    }

    private boolean persistent() {
        return db != null;
    }

    public boolean favorited(long accountId, long workId) {
        if (!persistent()) {
            return memory.containsKey(key(accountId, workId));
        }
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM \"t_work_favorite\" WHERE \"accountId\"=? AND \"workId\"=?")) {
            ps.setLong(1, accountId);
            ps.setLong(2, workId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.warn("查询收藏失败 accountId={} workId={}: {}", accountId, workId, e.getMessage());
            return false;
        }
    }

    public void put(long accountId, long workId, long now) {
        if (!persistent()) {
            memory.putIfAbsent(key(accountId, workId), now);
            return;
        }
        String sql = "INSERT INTO \"t_work_favorite\" (\"accountId\",\"workId\",\"createdAt\") VALUES (?,?,?)"
                + " ON CONFLICT (\"accountId\",\"workId\") DO NOTHING";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            ps.setLong(2, workId);
            ps.setLong(3, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("写入收藏失败 accountId={} workId={}: {}", accountId, workId, e.getMessage());
        }
    }

    public void remove(long accountId, long workId) {
        if (!persistent()) {
            memory.remove(key(accountId, workId));
            return;
        }
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM \"t_work_favorite\" WHERE \"accountId\"=? AND \"workId\"=?")) {
            ps.setLong(1, accountId);
            ps.setLong(2, workId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("删除收藏失败 accountId={} workId={}: {}", accountId, workId, e.getMessage());
        }
    }

    public List<Long> workIdsOf(long accountId) {
        if (!persistent()) {
            List<long[]> rows = new ArrayList<>();
            String prefix = accountId + ":";
            for (Map.Entry<String, Long> e : memory.entrySet()) {
                if (e.getKey().startsWith(prefix)) {
                    long workId = Long.parseLong(e.getKey().substring(prefix.length()));
                    rows.add(new long[]{workId, e.getValue()});
                }
            }
            rows.sort(Comparator.comparingLong((long[] r) -> r[1]).reversed().thenComparingLong(r -> r[0]));
            List<Long> ids = new ArrayList<>();
            for (long[] r : rows) {
                ids.add(r[0]);
            }
            return ids;
        }
        List<Long> ids = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT \"workId\" FROM \"t_work_favorite\" WHERE \"accountId\"=?"
                             + " ORDER BY \"createdAt\" DESC, \"workId\" ASC")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            log.warn("列出收藏失败 accountId={}: {}", accountId, e.getMessage());
        }
        return ids;
    }

    private static String key(long accountId, long workId) {
        return accountId + ":" + workId;
    }
}
