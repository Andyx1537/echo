package com.echo.http.work;

import com.aengine.util.id.IDGenerator;
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
 * 作品审核工单（{@code t_work_moderation}）。无库时内存态，与落库同一套约束：
 * {@code (workId, contentVersion)} 防重；同一作品仅一张活动工单；
 * 处置按 {@code (id, stateVersion)} CAS，并与作品状态、审计同一事务。
 */
@Slf4j
public final class WorkModerationStore {

    private static final String COLUMNS = "\"id\",\"workId\",\"contentVersion\",\"state\",\"stateVersion\","
            + "\"submitBy\",\"reasonCode\",\"note\",\"snapshot\",\"handledBy\",\"handledAt\",\"createdAt\"";

    public static final String ACTION_APPROVE = "approve";
    public static final String ACTION_REJECT = "reject";
    public static final String ACTION_TAKEDOWN = "takedown";
    public static final String ACTION_RESTORE = "restore";

    private final PgDb db;
    private final IDGenerator ids;
    private final Map<Long, WorkModerationTicket> memory = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> memoryAudits = new ArrayList<>();
    private final Object lock = new Object();

    public WorkModerationStore(PgDb db, IDGenerator ids) {
        this.db = db;
        this.ids = ids;
    }

    private boolean persistent() {
        return db != null;
    }

    public WorkModerationTicket byId(long id) {
        if (!persistent()) {
            synchronized (lock) {
                return copyOf(memory.get(id));
            }
        }
        return queryOne("SELECT " + COLUMNS + " FROM \"t_work_moderation\" WHERE \"id\"=?", id);
    }

    public WorkModerationTicket activeByWork(long workId) {
        if (!persistent()) {
            synchronized (lock) {
                return memory.values().stream()
                        .filter(t -> t.workId == workId && WorkModerationTicket.State.active(t.state))
                        .findFirst()
                        .map(WorkModerationStore::copyOf)
                        .orElse(null);
            }
        }
        return queryOne("SELECT " + COLUMNS + " FROM \"t_work_moderation\""
                + " WHERE \"workId\"=? AND \"state\" IN ('queued','assigned','reviewing') LIMIT 1", workId);
    }

    public List<WorkModerationTicket> queue(long cursor, int limit) {
        int cap = Math.max(0, limit);
        if (!persistent()) {
            synchronized (lock) {
                return memory.values().stream()
                        .filter(t -> WorkModerationTicket.State.active(t.state) && t.id > cursor)
                        .sorted(Comparator.comparingLong((WorkModerationTicket t) -> t.createdAt)
                                .thenComparingLong(t -> t.id))
                        .limit(cap)
                        .map(WorkModerationStore::copyOf)
                        .toList();
            }
        }
        List<WorkModerationTicket> out = new ArrayList<>();
        String sql = "SELECT " + COLUMNS + " FROM \"t_work_moderation\""
                + " WHERE \"state\" IN ('queued','assigned','reviewing') AND \"id\">?"
                + " ORDER BY \"createdAt\" ASC, \"id\" ASC LIMIT ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cursor);
            ps.setInt(2, cap);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("[work-moderation] 队列读取失败: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 新作品 pending 与工单同事务。任一失败整笔不成。
     */
    public boolean submitNew(WorkStore works, Work work, WorkModerationTicket ticket) {
        if (works == null || work == null || ticket == null) {
            return false;
        }
        if (!persistent()) {
            synchronized (works.slotLock()) {
                synchronized (lock) {
                    if (conflictsNew(ticket)) {
                        return false;
                    }
                    if (!works.insert(work)) {
                        return false;
                    }
                    memory.put(ticket.id, copyOf(ticket));
                    return true;
                }
            }
        }
        try {
            return db.inTransaction(c -> {
                if (!works.insertOn(c, work)) {
                    return false;
                }
                return insertOn(c, ticket);
            });
        } catch (SQLException e) {
            log.warn("[work-moderation] 送审失败 workId={}: {}", work.id, e.getMessage());
            return false;
        }
    }

    /**
     * 驳回重提与新工单同事务。
     */
    public boolean submitResubmit(WorkStore works, Work work, int expectedVersion,
                                  WorkModerationTicket ticket) {
        if (works == null || work == null || ticket == null) {
            return false;
        }
        if (!persistent()) {
            synchronized (works.slotLock()) {
                synchronized (lock) {
                    if (conflictsNew(ticket)) {
                        return false;
                    }
                    if (!works.casResubmit(work, expectedVersion)) {
                        return false;
                    }
                    memory.put(ticket.id, copyOf(ticket));
                    return true;
                }
            }
        }
        try {
            return db.inTransaction(c -> {
                if (!works.casResubmitOn(c, work, expectedVersion)) {
                    return false;
                }
                return insertOn(c, ticket);
            });
        } catch (SQLException e) {
            log.warn("[work-moderation] 重提工单失败 workId={}: {}", work.id, e.getMessage());
            return false;
        }
    }

    /**
     * 凭证复用直接公开：作品、凭证消费、已通过工单同事务。
     */
    public boolean submitReused(WorkStore works, WorkReviewEvidenceStore evidence, long evidenceId,
                                Work work, WorkModerationTicket ticket) {
        if (works == null || evidence == null || work == null || ticket == null) {
            return false;
        }
        if (!persistent()) {
            synchronized (works.slotLock()) {
                synchronized (evidence.lock()) {
                    synchronized (lock) {
                        if (conflictsNew(ticket)) {
                            return false;
                        }
                        if (!works.insertConsumingEvidence(work, evidence, evidenceId)) {
                            return false;
                        }
                        memory.put(ticket.id, copyOf(ticket));
                        return true;
                    }
                }
            }
        }
        try {
            return db.inTransaction(c -> {
                if (!works.insertConsumingEvidenceOn(c, evidence, evidenceId, work)) {
                    return false;
                }
                return insertOn(c, ticket);
            });
        } catch (SQLException e) {
            log.warn("[work-moderation] 复用公开工单失败 workId={}: {}", work.id, e.getMessage());
            return false;
        }
    }

    public HandleResult handle(WorkStore works, HandleCommand cmd) {
        if (works == null || cmd == null) {
            return null;
        }
        if (!persistent()) {
            synchronized (works.slotLock()) {
                synchronized (lock) {
                    return handleMemory(works, cmd);
                }
            }
        }
        try {
            return db.inTransaction(c -> handleOn(c, works, cmd));
        } catch (SQLException e) {
            log.warn("[work-moderation] 处置失败 id={}: {}", cmd.moderationId, e.getMessage());
            return null;
        }
    }

    public List<Map<String, Object>> memoryAudits() {
        synchronized (lock) {
            return List.copyOf(memoryAudits);
        }
    }

    private HandleResult handleMemory(WorkStore works, HandleCommand cmd) {
        WorkModerationTicket t = memory.get(cmd.moderationId);
        Transition step = transition(cmd.action);
        if (t == null || step == null || t.stateVersion != cmd.expectedStateVersion
                || !step.matchesTicket(t.state)) {
            return null;
        }
        if (!works.applyOperatorDecision(t.workId, step.workFrom, step.workTo,
                step.writeReviewedAt, cmd.now)) {
            return null;
        }
        t.state = step.ticketTo;
        t.stateVersion = t.stateVersion + 1;
        t.reasonCode = cmd.reasonCode;
        t.note = cmd.note;
        t.handledBy = cmd.operatorId;
        t.handledAt = cmd.now;
        memoryAudits.add(Map.of(
                "action", auditAction(cmd.action),
                "targetType", "work",
                "targetId", String.valueOf(t.workId),
                "moderationId", String.valueOf(t.id)));
        Work work = works.byId(t.workId);
        HandleResult r = new HandleResult();
        r.moderationId = t.id;
        r.workId = t.workId;
        r.state = t.state;
        r.workStatus = work == null ? step.workTo : work.status;
        r.reviewedAt = work == null ? null : work.reviewedAt;
        r.handledAt = cmd.now;
        r.stateVersion = t.stateVersion;
        return r;
    }

    private HandleResult handleOn(Connection conn, WorkStore works, HandleCommand cmd)
            throws SQLException {
        WorkModerationTicket t = readForUpdate(conn, cmd.moderationId);
        Transition step = transition(cmd.action);
        if (t == null || step == null || t.stateVersion != cmd.expectedStateVersion
                || !step.matchesTicket(t.state)) {
            return null;
        }
        if (!works.applyOperatorDecisionOn(conn, t.workId, step.workFrom, step.workTo,
                step.writeReviewedAt, cmd.now)) {
            return null;
        }
        String sql = "UPDATE \"t_work_moderation\" SET \"state\"=?,\"stateVersion\"=\"stateVersion\"+1,"
                + "\"reasonCode\"=?,\"note\"=?,\"handledBy\"=?,\"handledAt\"=?"
                + " WHERE \"id\"=? AND \"stateVersion\"=? AND \"state\"=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, step.ticketTo);
            ps.setString(2, cmd.reasonCode);
            ps.setString(3, cmd.note);
            ps.setLong(4, cmd.operatorId);
            ps.setLong(5, cmd.now);
            ps.setLong(6, cmd.moderationId);
            ps.setInt(7, cmd.expectedStateVersion);
            ps.setString(8, t.state);
            if (ps.executeUpdate() == 0) {
                return null;
            }
        }
        insertAudit(conn, cmd, t.workId);
        Work work = works.byIdOn(conn, t.workId);
        WorkModerationTicket after = readForUpdate(conn, cmd.moderationId);
        HandleResult r = new HandleResult();
        r.moderationId = cmd.moderationId;
        r.workId = t.workId;
        r.state = after == null ? step.ticketTo : after.state;
        r.workStatus = work == null ? step.workTo : work.status;
        r.reviewedAt = work == null ? null : work.reviewedAt;
        r.handledAt = cmd.now;
        r.stateVersion = after == null ? cmd.expectedStateVersion + 1 : after.stateVersion;
        return r;
    }

    private boolean conflictsNew(WorkModerationTicket ticket) {
        return memory.values().stream().anyMatch(existing ->
                existing.workId == ticket.workId
                        && (existing.contentVersion == ticket.contentVersion
                        || WorkModerationTicket.State.active(existing.state)));
    }

    private boolean insertOn(Connection conn, WorkModerationTicket t) throws SQLException {
        String sql = "INSERT INTO \"t_work_moderation\" (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, t);
            return ps.executeUpdate() > 0;
        }
    }

    private WorkModerationTicket readForUpdate(Connection conn, long id) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM \"t_work_moderation\" WHERE \"id\"=? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    private void insertAudit(Connection conn, HandleCommand cmd, long workId) throws SQLException {
        String sql = "INSERT INTO \"t_audit_log\""
                + " (\"id\",\"actor\",\"actorType\",\"action\",\"targetType\",\"targetId\",\"scope\",\"ts\")"
                + " VALUES (?,?,?,?,?,?,?::jsonb,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ids.nextId());
            ps.setString(2, String.valueOf(cmd.operatorId));
            ps.setString(3, "staff");
            ps.setString(4, auditAction(cmd.action));
            ps.setString(5, "work");
            ps.setString(6, String.valueOf(workId));
            ps.setString(7, "{\"moderationId\":\"" + cmd.moderationId + "\",\"action\":\""
                    + cmd.action + "\",\"reasonCode\":\""
                    + (cmd.reasonCode == null ? "" : cmd.reasonCode) + "\"}");
            ps.setLong(8, cmd.now);
            ps.executeUpdate();
        }
    }

    private WorkModerationTicket queryOne(String sql, long id) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException e) {
            log.warn("[work-moderation] 读取失败: {}", e.getMessage());
            return null;
        }
    }

    private static void bind(PreparedStatement ps, WorkModerationTicket t) throws SQLException {
        int i = 1;
        ps.setLong(i++, t.id);
        ps.setLong(i++, t.workId);
        ps.setInt(i++, t.contentVersion);
        ps.setString(i++, t.state);
        ps.setInt(i++, t.stateVersion);
        ps.setLong(i++, t.submitBy);
        ps.setString(i++, t.reasonCode);
        ps.setString(i++, t.note);
        ps.setString(i++, t.snapshotJson);
        if (t.handledBy == null) {
            ps.setNull(i++, Types.BIGINT);
        } else {
            ps.setLong(i++, t.handledBy);
        }
        if (t.handledAt == null) {
            ps.setNull(i++, Types.BIGINT);
        } else {
            ps.setLong(i++, t.handledAt);
        }
        ps.setLong(i, t.createdAt);
    }

    private static WorkModerationTicket read(ResultSet rs) throws SQLException {
        WorkModerationTicket t = new WorkModerationTicket();
        t.id = rs.getLong("id");
        t.workId = rs.getLong("workId");
        t.contentVersion = rs.getInt("contentVersion");
        t.state = rs.getString("state");
        t.stateVersion = rs.getInt("stateVersion");
        t.submitBy = rs.getLong("submitBy");
        t.reasonCode = rs.getString("reasonCode");
        t.note = rs.getString("note");
        t.snapshotJson = rs.getString("snapshot");
        long handledBy = rs.getLong("handledBy");
        t.handledBy = rs.wasNull() ? null : handledBy;
        long handledAt = rs.getLong("handledAt");
        t.handledAt = rs.wasNull() ? null : handledAt;
        t.createdAt = rs.getLong("createdAt");
        return t;
    }

    private static WorkModerationTicket copyOf(WorkModerationTicket src) {
        if (src == null) {
            return null;
        }
        WorkModerationTicket t = new WorkModerationTicket();
        t.id = src.id;
        t.workId = src.workId;
        t.contentVersion = src.contentVersion;
        t.state = src.state;
        t.stateVersion = src.stateVersion;
        t.submitBy = src.submitBy;
        t.reasonCode = src.reasonCode;
        t.note = src.note;
        t.snapshotJson = src.snapshotJson;
        t.handledBy = src.handledBy;
        t.handledAt = src.handledAt;
        t.createdAt = src.createdAt;
        return t;
    }

    public static boolean knownAction(String action) {
        return transition(action) != null;
    }

    public static boolean requiresReason(String action) {
        return ACTION_REJECT.equals(action) || ACTION_TAKEDOWN.equals(action);
    }

    static String auditAction(String action) {
        if (ACTION_APPROVE.equals(action)) {
            return "moderation.approve";
        }
        if (ACTION_REJECT.equals(action)) {
            return "moderation.reject";
        }
        if (ACTION_TAKEDOWN.equals(action)) {
            return "moderation.takedown";
        }
        if (ACTION_RESTORE.equals(action)) {
            return "moderation.restore";
        }
        return "moderation.unknown";
    }

    private static Transition transition(String action) {
        if (ACTION_APPROVE.equals(action)) {
            return Transition.active(Work.Status.PENDING, Work.Status.PUBLIC,
                    WorkModerationTicket.State.APPROVED, true);
        }
        if (ACTION_REJECT.equals(action)) {
            return Transition.active(Work.Status.PENDING, Work.Status.REJECTED,
                    WorkModerationTicket.State.REJECTED, false);
        }
        if (ACTION_TAKEDOWN.equals(action)) {
            return Transition.of(WorkModerationTicket.State.APPROVED, Work.Status.PUBLIC,
                    Work.Status.TAKENDOWN, WorkModerationTicket.State.TAKENDOWN, false);
        }
        if (ACTION_RESTORE.equals(action)) {
            return Transition.of(WorkModerationTicket.State.TAKENDOWN, Work.Status.TAKENDOWN,
                    Work.Status.PUBLIC, WorkModerationTicket.State.APPROVED, false);
        }
        return null;
    }

    private static final class Transition {
        final String[] ticketFrom;
        final boolean activeFrom;
        final String workFrom;
        final String workTo;
        final String ticketTo;
        final boolean writeReviewedAt;

        private Transition(String[] ticketFrom, boolean activeFrom, String workFrom,
                           String workTo, String ticketTo, boolean writeReviewedAt) {
            this.ticketFrom = ticketFrom;
            this.activeFrom = activeFrom;
            this.workFrom = workFrom;
            this.workTo = workTo;
            this.ticketTo = ticketTo;
            this.writeReviewedAt = writeReviewedAt;
        }

        static Transition active(String workFrom, String workTo, String ticketTo,
                                 boolean writeReviewedAt) {
            return new Transition(new String[0], true, workFrom, workTo, ticketTo, writeReviewedAt);
        }

        static Transition of(String ticketFrom, String workFrom, String workTo,
                             String ticketTo, boolean writeReviewedAt) {
            return new Transition(new String[]{ticketFrom}, false, workFrom, workTo, ticketTo,
                    writeReviewedAt);
        }

        boolean matchesTicket(String state) {
            if (activeFrom) {
                return WorkModerationTicket.State.active(state);
            }
            for (String allowed : ticketFrom) {
                if (allowed.equals(state)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final class HandleCommand {
        public long moderationId;
        public int expectedStateVersion;
        public String action;
        public long operatorId;
        public String reasonCode;
        public String note;
        public long now;
    }

    public static final class HandleResult {
        public long moderationId;
        public long workId;
        public String state;
        public String workStatus;
        public Long reviewedAt;
        public long handledAt;
        public int stateVersion;
    }
}
