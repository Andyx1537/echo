package com.echo.http.work;

import com.echo.infra.persistence.PgDb;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公开审核凭证。无 DB 时内存态，语义与落库一致。
 */
@Slf4j
public final class WorkReviewEvidenceStore {

    private static final String COLUMNS = "\"reviewEvidenceId\",\"sourceCardId\",\"sourceContentVersion\","
            + "\"result\",\"contentHash\",\"ownerAccountId\",\"policyVersion\",\"policyEpoch\","
            + "\"reviewedAt\",\"expiresAt\",\"aigcLabelReady\",\"consentRevoked\","
            + "\"consumedByWorkId\",\"invalidatedAt\",\"invalidationReason\"";

    private final PgDb db;
    private final Map<Long, WorkReviewEvidence> memory = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public WorkReviewEvidenceStore(PgDb db) {
        this.db = db;
    }

    Object lock() {
        return lock;
    }

    private boolean persistent() {
        return db != null;
    }

    public void put(WorkReviewEvidence evidence) {
        if (evidence == null) {
            return;
        }
        if (!persistent()) {
            synchronized (lock) {
                memory.put(evidence.reviewEvidenceId, copyOf(evidence));
            }
            return;
        }
        String sql = "INSERT INTO \"t_public_review_evidence\" (" + COLUMNS + ") VALUES ("
                + "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (\"reviewEvidenceId\") DO UPDATE SET "
                + "\"sourceCardId\"=EXCLUDED.\"sourceCardId\","
                + "\"sourceContentVersion\"=EXCLUDED.\"sourceContentVersion\","
                + "\"result\"=EXCLUDED.\"result\","
                + "\"contentHash\"=EXCLUDED.\"contentHash\","
                + "\"ownerAccountId\"=EXCLUDED.\"ownerAccountId\","
                + "\"policyVersion\"=EXCLUDED.\"policyVersion\","
                + "\"policyEpoch\"=EXCLUDED.\"policyEpoch\","
                + "\"reviewedAt\"=EXCLUDED.\"reviewedAt\","
                + "\"expiresAt\"=EXCLUDED.\"expiresAt\","
                + "\"aigcLabelReady\"=EXCLUDED.\"aigcLabelReady\","
                + "\"consentRevoked\"=EXCLUDED.\"consentRevoked\","
                + "\"consumedByWorkId\"=EXCLUDED.\"consumedByWorkId\","
                + "\"invalidatedAt\"=EXCLUDED.\"invalidatedAt\","
                + "\"invalidationReason\"=EXCLUDED.\"invalidationReason\"";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, evidence);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("[review-evidence] 写入失败 id={}: {}", evidence.reviewEvidenceId, e.getMessage());
        }
    }

    public WorkReviewEvidence byId(Long id) {
        if (id == null) {
            return null;
        }
        if (!persistent()) {
            synchronized (lock) {
                WorkReviewEvidence found = memory.get(id);
                return found == null ? null : copyOf(found);
            }
        }
        return queryOne("SELECT " + COLUMNS + " FROM \"t_public_review_evidence\" WHERE \"reviewEvidenceId\"=?", id);
    }

    public WorkReviewEvidence reusableByCard(long cardId) {
        if (!persistent()) {
            synchronized (lock) {
                return memory.values().stream()
                        .filter(e -> e.sourceCardId == cardId && e.consumedByWorkId == null)
                        .max((a, b) -> Long.compare(a.reviewedAt, b.reviewedAt))
                        .map(WorkReviewEvidenceStore::copyOf)
                        .orElse(null);
            }
        }
        return queryOne("SELECT " + COLUMNS + " FROM \"t_public_review_evidence\""
                + " WHERE \"sourceCardId\"=? AND \"consumedByWorkId\" IS NULL"
                + " ORDER BY \"reviewedAt\" DESC LIMIT 1", cardId);
    }

    public boolean tryConsume(long evidenceId, long workId) {
        if (!persistent()) {
            synchronized (lock) {
                WorkReviewEvidence found = memory.get(evidenceId);
                if (found == null || found.consumedByWorkId != null) {
                    return false;
                }
                found.consumedByWorkId = workId;
                return true;
            }
        }
        try {
            return db.inTransaction(c -> tryConsume(c, evidenceId, workId));
        } catch (SQLException e) {
            log.warn("[review-evidence] 消费失败 id={}: {}", evidenceId, e.getMessage());
            return false;
        }
    }

    boolean tryConsume(Connection conn, long evidenceId, long workId) throws SQLException {
        String sql = "UPDATE \"t_public_review_evidence\" SET \"consumedByWorkId\"=?"
                + " WHERE \"reviewEvidenceId\"=? AND \"consumedByWorkId\" IS NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, workId);
            ps.setLong(2, evidenceId);
            return ps.executeUpdate() == 1;
        }
    }

    private WorkReviewEvidence queryOne(String sql, long id) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException e) {
            log.warn("[review-evidence] 查询失败: {}", e.getMessage());
            return null;
        }
    }

    private static WorkReviewEvidence copyOf(WorkReviewEvidence src) {
        WorkReviewEvidence copy = new WorkReviewEvidence();
        copy.reviewEvidenceId = src.reviewEvidenceId;
        copy.sourceCardId = src.sourceCardId;
        copy.sourceContentVersion = src.sourceContentVersion;
        copy.result = src.result;
        copy.contentHash = src.contentHash;
        copy.ownerAccountId = src.ownerAccountId;
        copy.policyVersion = src.policyVersion;
        copy.policyEpoch = src.policyEpoch;
        copy.reviewedAt = src.reviewedAt;
        copy.expiresAt = src.expiresAt;
        copy.aigcLabelReady = src.aigcLabelReady;
        copy.consentRevoked = src.consentRevoked;
        copy.consumedByWorkId = src.consumedByWorkId;
        copy.invalidatedAt = src.invalidatedAt;
        copy.invalidationReason = src.invalidationReason;
        return copy;
    }

    private static WorkReviewEvidence read(ResultSet rs) throws SQLException {
        WorkReviewEvidence evidence = new WorkReviewEvidence();
        evidence.reviewEvidenceId = rs.getLong("reviewEvidenceId");
        evidence.sourceCardId = rs.getLong("sourceCardId");
        evidence.sourceContentVersion = rs.getInt("sourceContentVersion");
        evidence.result = rs.getString("result");
        evidence.contentHash = rs.getString("contentHash");
        evidence.ownerAccountId = rs.getLong("ownerAccountId");
        evidence.policyVersion = rs.getString("policyVersion");
        evidence.policyEpoch = rs.getInt("policyEpoch");
        evidence.reviewedAt = rs.getLong("reviewedAt");
        evidence.expiresAt = rs.getLong("expiresAt");
        evidence.aigcLabelReady = rs.getBoolean("aigcLabelReady");
        evidence.consentRevoked = rs.getBoolean("consentRevoked");
        long consumed = rs.getLong("consumedByWorkId");
        evidence.consumedByWorkId = rs.wasNull() ? null : consumed;
        long invalidated = rs.getLong("invalidatedAt");
        evidence.invalidatedAt = rs.wasNull() ? null : invalidated;
        evidence.invalidationReason = rs.getString("invalidationReason");
        return evidence;
    }

    private static void bind(PreparedStatement ps, WorkReviewEvidence evidence) throws SQLException {
        int i = 1;
        ps.setLong(i++, evidence.reviewEvidenceId);
        ps.setLong(i++, evidence.sourceCardId);
        ps.setInt(i++, evidence.sourceContentVersion);
        ps.setString(i++, evidence.result);
        ps.setString(i++, evidence.contentHash);
        ps.setLong(i++, evidence.ownerAccountId);
        ps.setString(i++, evidence.policyVersion == null ? "" : evidence.policyVersion);
        ps.setInt(i++, evidence.policyEpoch);
        ps.setLong(i++, evidence.reviewedAt);
        ps.setLong(i++, evidence.expiresAt);
        ps.setBoolean(i++, evidence.aigcLabelReady);
        ps.setBoolean(i++, evidence.consentRevoked);
        if (evidence.consumedByWorkId == null) {
            ps.setNull(i++, Types.BIGINT);
        } else {
            ps.setLong(i++, evidence.consumedByWorkId);
        }
        if (evidence.invalidatedAt == null) {
            ps.setNull(i++, Types.BIGINT);
        } else {
            ps.setLong(i++, evidence.invalidatedAt);
        }
        ps.setString(i, evidence.invalidationReason);
    }
}
