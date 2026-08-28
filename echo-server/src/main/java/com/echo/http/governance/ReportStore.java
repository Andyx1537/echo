package com.echo.http.governance;

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

/**
 * 举报存储（{@code t_report}）。无 DB 时退化为内存态。
 *
 * <p>🔴 去重语义落在存储层：{@link #insertIfNoOpen} 在已有未处理举报时返回 null，
 * 而不是先查再写（先查再写有竞态窗口）。落库态靠部分唯一索引
 * {@code t_report_uk_reporter_target_open}，内存态靠同步块。</p>
 */
@Slf4j
public final class ReportStore {

    /** 一条举报。{@code cardId} 仅 {@code targetType='card'} 时非空。 */
    public record Row(long id, long reporterId, String targetType, long targetId, Long cardId,
                      String reasonCode, String note, String status, long createdAt,
                      Long handledAt, Long handledBy, Long moderationId) {
    }

    private final PgDb db;
    private final Map<Long, Row> mem = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public ReportStore(PgDb db) {
        this.db = db;
    }

    /**
     * 插入一条举报，但仅当该举报人对该对象<b>没有未处理举报</b>时。
     *
     * @return 新建的行；已有未处理举报则返回 null
     */
    public Row insertIfNoOpen(long id, long reporterId, String targetType, long targetId,
                              String reasonCode, String note, long now) {
        Long cardId = ReportService.TARGET_CARD.equals(targetType) ? targetId : null;
        Row row = new Row(id, reporterId, targetType, targetId, cardId, reasonCode, note,
                "open", now, null, null, null);
        if (db == null) {
            synchronized (writeLock) {
                boolean exists = mem.values().stream().anyMatch(r ->
                        r.reporterId() == reporterId
                                && r.targetType().equals(targetType)
                                && r.targetId() == targetId
                                && "open".equals(r.status()));
                if (exists) {
                    return null;
                }
                mem.put(id, row);
                return row;
            }
        }
        String sql = """
                INSERT INTO "t_report"
                    ("id","cardId","reporterId","reasonCode","note","status","createdAt",
                     "targetType","targetId")
                SELECT ?,?,?,?,?,'open',?,?,?
                 WHERE NOT EXISTS (
                     SELECT 1 FROM "t_report"
                      WHERE "reporterId" = ? AND "targetType" = ? AND "targetId" = ?
                        AND "status" = 'open')
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            if (cardId == null) {
                ps.setNull(2, Types.BIGINT);
            } else {
                ps.setLong(2, cardId);
            }
            ps.setLong(3, reporterId);
            ps.setString(4, reasonCode);
            if (note == null) {
                ps.setNull(5, Types.VARCHAR);
            } else {
                ps.setString(5, note);
            }
            ps.setLong(6, now);
            ps.setString(7, targetType);
            ps.setLong(8, targetId);
            ps.setLong(9, reporterId);
            ps.setString(10, targetType);
            ps.setLong(11, targetId);
            return ps.executeUpdate() == 0 ? null : row;
        } catch (SQLException e) {
            // 唯一索引撞了也是"已有未处理举报"，与 NOT EXISTS 未命中同义
            if (isUniqueViolation(e)) {
                return null;
            }
            throw new RuntimeException("写 t_report 失败", e);
        }
    }

    private static boolean isUniqueViolation(SQLException e) {
        return "23505".equals(e.getSQLState());
    }

    /** 某人提交过的举报，按时间倒序。 */
    public List<Row> byReporter(long reporterId, int limit) {
        if (db == null) {
            return mem.values().stream()
                    .filter(r -> r.reporterId() == reporterId)
                    .sorted(Comparator.comparingLong(Row::createdAt).reversed())
                    .limit(limit)
                    .toList();
        }
        String sql = """
                SELECT "id","reporterId","targetType","targetId","cardId","reasonCode","note",
                       "status","createdAt","handledAt","handledBy","moderationId"
                  FROM "t_report" WHERE "reporterId" = ?
                 ORDER BY "createdAt" DESC LIMIT ?
                """;
        List<Row> out = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, reporterId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读 t_report 失败", e);
        }
        return out;
    }

    /** 按对象查未处理举报数（审核台用；C 端不暴露）。 */
    public int openCount(String targetType, long targetId) {
        if (db == null) {
            return (int) mem.values().stream().filter(r -> r.targetType().equals(targetType)
                    && r.targetId() == targetId && "open".equals(r.status())).count();
        }
        String sql = "SELECT count(*) FROM \"t_report\" WHERE \"targetType\"=? AND \"targetId\"=?"
                + " AND \"status\"='open'";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetType);
            ps.setLong(2, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("统计 t_report 失败", e);
        }
    }

    /** 标记为已处理（审核台处置后调用）。 */
    public void markHandled(long reportId, long handledBy, Long moderationId, long now) {
        if (db == null) {
            Row r = mem.get(reportId);
            if (r != null) {
                mem.put(reportId, new Row(r.id(), r.reporterId(), r.targetType(), r.targetId(),
                        r.cardId(), r.reasonCode(), r.note(), "handled", r.createdAt(),
                        now, handledBy, moderationId));
            }
            return;
        }
        String sql = "UPDATE \"t_report\" SET \"status\"='handled',\"handledAt\"=?,"
                + "\"handledBy\"=?,\"moderationId\"=? WHERE \"id\"=?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, handledBy);
            if (moderationId == null) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, moderationId);
            }
            ps.setLong(4, reportId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新 t_report 失败", e);
        }
    }

    private static Row map(ResultSet rs) throws SQLException {
        long cardId = rs.getLong("cardId");
        boolean cardNull = rs.wasNull();
        long handledAt = rs.getLong("handledAt");
        boolean handledAtNull = rs.wasNull();
        long handledBy = rs.getLong("handledBy");
        boolean handledByNull = rs.wasNull();
        long modId = rs.getLong("moderationId");
        boolean modNull = rs.wasNull();
        return new Row(rs.getLong("id"), rs.getLong("reporterId"), rs.getString("targetType"),
                rs.getLong("targetId"), cardNull ? null : cardId, rs.getString("reasonCode"),
                rs.getString("note"), rs.getString("status"), rs.getLong("createdAt"),
                handledAtNull ? null : handledAt, handledByNull ? null : handledBy,
                modNull ? null : modId);
    }
}
