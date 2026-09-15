package com.echo.http.work;

import com.echo.infra.persistence.PgDb;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 作品评论。无库时内存态，有库时读写 {@code t_work_comment}。 */
@Slf4j
public final class WorkCommentStore {
    private static final String COLUMNS = "\"id\",\"workId\",\"authorId\",\"rootCommentId\",\"replyToCommentId\","
            + "\"body\",\"createdAt\",\"updatedAt\",\"displayState\",\"stateVersion\","
            + "\"deletedAt\",\"deletedBy\",\"deleteReason\"";

    private final PgDb db;
    private final Map<Long, WorkComment> memory = new ConcurrentHashMap<>();

    public WorkCommentStore(PgDb db) {
        this.db = db;
    }

    private boolean persistent() {
        return db != null;
    }

    public void insert(WorkComment c) {
        if (!persistent()) {
            memory.put(c.id, c);
            return;
        }
        String sql = "INSERT INTO \"t_work_comment\" (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, c);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("插入评论失败 id={} workId={}: {}", c.id, c.workId, e.getMessage());
        }
    }

    public WorkComment byId(long id) {
        if (!persistent()) {
            return memory.get(id);
        }
        List<WorkComment> got = query("SELECT " + COLUMNS + " FROM \"t_work_comment\" WHERE \"id\"=?", List.of(id));
        return got.isEmpty() ? null : got.get(0);
    }

    public List<WorkComment> ofWork(long workId) {
        if (!persistent()) {
            List<WorkComment> out = new ArrayList<>();
            for (WorkComment c : memory.values()) {
                if (c.workId == workId) {
                    out.add(c);
                }
            }
            return out;
        }
        return query("SELECT " + COLUMNS + " FROM \"t_work_comment\" WHERE \"workId\"=?", List.of(workId));
    }

    public List<WorkComment> visibleRoots(long workId, String sort) {
        List<WorkComment> roots = new ArrayList<>();
        for (WorkComment c : ofWork(workId)) {
            if (c.root() && c.publiclyVisible()) {
                roots.add(c);
            }
        }
        if ("latest".equals(sort)) {
            roots.sort(Comparator.comparingLong((WorkComment c) -> c.createdAt).reversed()
                    .thenComparing(Comparator.comparingLong((WorkComment c) -> c.id).reversed()));
        } else {
            roots.sort(Comparator
                    .comparingLong((WorkComment c) -> hotScore(c)).reversed()
                    .thenComparing(Comparator.comparingLong((WorkComment c) -> c.id).reversed()));
        }
        return roots;
    }

    public List<WorkComment> visibleReplies(long rootId) {
        List<WorkComment> replies = new ArrayList<>();
        if (!persistent()) {
            for (WorkComment c : memory.values()) {
                if (c.rootCommentId != null && c.rootCommentId == rootId && c.publiclyVisible()) {
                    replies.add(c);
                }
            }
        } else {
            for (WorkComment c : query("SELECT " + COLUMNS + " FROM \"t_work_comment\" WHERE \"rootCommentId\"=?",
                    List.of(rootId))) {
                if (c.publiclyVisible()) {
                    replies.add(c);
                }
            }
        }
        replies.sort(Comparator.comparingLong((WorkComment c) -> c.createdAt)
                .thenComparingLong(c -> c.id));
        return replies;
    }

    public int visibleCount(long workId) {
        int n = 0;
        for (WorkComment c : ofWork(workId)) {
            if (c.publiclyVisible()) {
                n++;
            }
        }
        return n;
    }

    public int visibleReplyCount(long rootId) {
        return visibleReplies(rootId).size();
    }

    public int cascadeSoftDelete(WorkComment root, long now, long by, String reason) {
        int n = 0;
        if (softDelete(root, now, by, reason)) {
            n++;
        }
        for (WorkComment c : ofWork(root.workId)) {
            if (c.rootCommentId != null && c.rootCommentId == root.id && softDelete(c, now, by, reason)) {
                n++;
            }
        }
        return n;
    }

    public boolean softDelete(WorkComment c, long now, long by, String reason) {
        if (c.deletedAt != null) {
            return false;
        }
        c.deletedAt = now;
        c.deletedBy = by;
        c.deleteReason = reason;
        c.displayState = WorkComment.HIDDEN;
        c.updatedAt = now;
        c.stateVersion++;
        persist(c);
        return true;
    }

    public void hide(WorkComment c, long now) {
        c.displayState = WorkComment.OWNER_HIDDEN;
        c.updatedAt = now;
        c.stateVersion++;
        persist(c);
    }

    public void restore(WorkComment c, long now) {
        c.displayState = WorkComment.VISIBLE;
        c.updatedAt = now;
        c.stateVersion++;
        persist(c);
    }

    public long hotScore(WorkComment root) {
        return visibleReplyCount(root.id) * 1_000_000L + root.createdAt / 1000L;
    }

    private void persist(WorkComment c) {
        if (!persistent()) {
            memory.put(c.id, c);
            return;
        }
        String sql = "UPDATE \"t_work_comment\" SET \"displayState\"=?,\"stateVersion\"=?,\"updatedAt\"=?,"
                + "\"deletedAt\"=?,\"deletedBy\"=?,\"deleteReason\"=? WHERE \"id\"=?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, c.displayState);
            ps.setInt(2, c.stateVersion);
            ps.setLong(3, c.updatedAt);
            setNullableLong(ps, 4, c.deletedAt);
            setNullableLong(ps, 5, c.deletedBy);
            ps.setString(6, c.deleteReason);
            ps.setLong(7, c.id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("更新评论失败 id={}: {}", c.id, e.getMessage());
        }
    }

    private List<WorkComment> query(String sql, List<Object> args) {
        List<WorkComment> out = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("查询评论失败: {}", e.getMessage());
        }
        return out;
    }

    private static WorkComment read(ResultSet rs) throws SQLException {
        WorkComment c = new WorkComment();
        c.id = rs.getLong("id");
        c.workId = rs.getLong("workId");
        c.authorId = rs.getLong("authorId");
        c.rootCommentId = nullableLong(rs, "rootCommentId");
        c.replyToCommentId = nullableLong(rs, "replyToCommentId");
        c.body = rs.getString("body");
        c.createdAt = rs.getLong("createdAt");
        c.updatedAt = rs.getLong("updatedAt");
        c.displayState = rs.getString("displayState");
        c.stateVersion = rs.getInt("stateVersion");
        c.deletedAt = nullableLong(rs, "deletedAt");
        c.deletedBy = nullableLong(rs, "deletedBy");
        c.deleteReason = rs.getString("deleteReason");
        return c;
    }

    private static void bind(PreparedStatement ps, WorkComment c) throws SQLException {
        int i = 1;
        ps.setLong(i++, c.id);
        ps.setLong(i++, c.workId);
        ps.setLong(i++, c.authorId);
        setNullableLong(ps, i++, c.rootCommentId);
        setNullableLong(ps, i++, c.replyToCommentId);
        ps.setString(i++, c.body == null ? "" : c.body);
        ps.setLong(i++, c.createdAt);
        ps.setLong(i++, c.updatedAt);
        ps.setString(i++, c.displayState);
        ps.setInt(i++, c.stateVersion);
        setNullableLong(ps, i++, c.deletedAt);
        setNullableLong(ps, i++, c.deletedBy);
        ps.setString(i, c.deleteReason);
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, Types.BIGINT);
        } else {
            ps.setLong(idx, v);
        }
    }
}
