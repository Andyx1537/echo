package com.echo.http.work;

import com.echo.infra.persistence.PgDb;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 匿名广场两小时一批。进程内 Map 挡不住重启和多实例换批；有库时读写 {@code t_anon_plaza_batch}。
 */
@Slf4j
public final class AnonPlazaBatchStore {
    public static final int BATCH_SIZE = 30;
    public static final long TTL_MS = 2L * 60 * 60 * 1000;

    private final PgDb db;
    private final ConcurrentHashMap<Long, Batch> memory = new ConcurrentHashMap<>();

    public AnonPlazaBatchStore(PgDb db) {
        this.db = db;
    }

    private boolean persistent() {
        return db != null;
    }

    public List<Work> freeze(long viewer, List<Work> visible, long now) {
        Map<Long, Work> byId = new LinkedHashMap<>();
        for (Work work : visible) {
            byId.put(work.id, work);
        }
        Batch existing = load(viewer);
        if (existing != null && now - existing.startedAt < TTL_MS) {
            return replay(existing.workIds, byId);
        }
        List<Work> batch = visible.size() <= BATCH_SIZE
                ? List.copyOf(visible)
                : List.copyOf(visible.subList(0, BATCH_SIZE));
        List<Long> ids = new ArrayList<>(batch.size());
        for (Work work : batch) {
            ids.add(work.id);
        }
        Batch created = new Batch(List.copyOf(ids), now);
        Batch winner = save(viewer, created, existing);
        if (winner != created) {
            return replay(winner.workIds, byId);
        }
        return batch;
    }

    private List<Work> replay(List<Long> ids, Map<Long, Work> byId) {
        List<Work> kept = new ArrayList<>();
        for (Long id : ids) {
            Work work = byId.get(id);
            if (work != null) {
                kept.add(work);
            }
        }
        return kept;
    }

    private Batch load(long viewer) {
        if (!persistent()) {
            return memory.get(viewer);
        }
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT \"workIds\",\"startedAt\" FROM \"t_anon_plaza_batch\" WHERE \"accountId\"=?")) {
            ps.setLong(1, viewer);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Batch(decode(rs.getString("workIds")), rs.getLong("startedAt"));
            }
        } catch (SQLException e) {
            log.warn("读取匿名广场批次失败 accountId={}: {}", viewer, e.getMessage());
            return null;
        }
    }

    private Batch save(long viewer, Batch created, Batch previous) {
        if (!persistent()) {
            Batch current = memory.get(viewer);
            if (current != null && created.startedAt - current.startedAt < TTL_MS) {
                return current;
            }
            memory.put(viewer, created);
            return created;
        }
        try {
            return db.inTransaction(conn -> persist(conn, viewer, created, previous));
        } catch (SQLException e) {
            log.warn("写入匿名广场批次失败 accountId={}: {}", viewer, e.getMessage());
            Batch raced = load(viewer);
            return raced != null ? raced : created;
        }
    }

    private static Batch persist(Connection conn, long viewer, Batch created, Batch previous) throws SQLException {
        try (PreparedStatement lock = conn.prepareStatement(
                "SELECT \"workIds\",\"startedAt\" FROM \"t_anon_plaza_batch\" WHERE \"accountId\"=? FOR UPDATE")) {
            lock.setLong(1, viewer);
            try (ResultSet rs = lock.executeQuery()) {
                if (rs.next()) {
                    Batch current = new Batch(decode(rs.getString("workIds")), rs.getLong("startedAt"));
                    if (created.startedAt - current.startedAt < TTL_MS) {
                        return current;
                    }
                }
            }
        }
        if (previous == null) {
            insertIgnore(conn, viewer, created);
        } else {
            try (PreparedStatement update = conn.prepareStatement(
                    "UPDATE \"t_anon_plaza_batch\" SET \"workIds\"=?,\"startedAt\"=?"
                            + " WHERE \"accountId\"=? AND \"startedAt\"=?")) {
                update.setString(1, encode(created.workIds));
                update.setLong(2, created.startedAt);
                update.setLong(3, viewer);
                update.setLong(4, previous.startedAt);
                if (update.executeUpdate() == 0) {
                    insertIgnore(conn, viewer, created);
                }
            }
        }
        Batch stored = read(conn, viewer);
        return stored != null ? stored : created;
    }

    private static void insertIgnore(Connection conn, long viewer, Batch created) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO \"t_anon_plaza_batch\" (\"accountId\",\"workIds\",\"startedAt\") VALUES (?,?,?)"
                        + " ON CONFLICT (\"accountId\") DO NOTHING")) {
            insert.setLong(1, viewer);
            insert.setString(2, encode(created.workIds));
            insert.setLong(3, created.startedAt);
            insert.executeUpdate();
        }
    }

    private static Batch read(Connection conn, long viewer) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT \"workIds\",\"startedAt\" FROM \"t_anon_plaza_batch\" WHERE \"accountId\"=?")) {
            ps.setLong(1, viewer);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Batch(decode(rs.getString("workIds")), rs.getLong("startedAt"));
            }
        }
    }

    static String encode(List<Long> ids) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(ids.get(i));
        }
        return out.toString();
    }

    static List<Long> decode(String raw) {
        List<Long> ids = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                ids.add(Long.parseLong(part.trim()));
            }
        }
        return ids;
    }

    record Batch(List<Long> workIds, long startedAt) {
    }
}
