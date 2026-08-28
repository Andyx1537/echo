package com.echo.http.store;

import com.aengine.util.id.IDGenerator;
import com.echo.http.model.ModerationModels.AuditAction;
import com.echo.http.model.ModerationModels.AuditLog;
import com.echo.http.model.ModerationModels.CardStatus;
import com.echo.http.model.ModerationModels.CardVisibilityLog;
import com.echo.http.model.ModerationModels.HandleCommand;
import com.echo.http.model.ModerationModels.HandleResult;
import com.echo.http.model.ModerationModels.MemoryCard;
import com.echo.http.model.ModerationModels.ModerationSetting;
import com.echo.http.model.ModerationModels.ModerationTicket;
import com.echo.http.model.ModerationModels.Report;
import com.echo.infra.persistence.PgDb;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 审核存储。DDL 见 {@code src/main/resources/sql/schema.sql}。
 *
 * <p>🔴 <b>两条硬约束在本类里是结构性的，不是注释</b>：</p>
 * <ul>
 *   <li>{@code MOD1 ③} 同一事务：{@link #handleAtomically} 与 {@link #appealAtomically} 的四次写
 *       （卡状态 / 工单 / 可见性流水 / 审计流水）全部跑在
 *       {@link PgDb#inTransaction(PgDb.TxWork)} 的一条连接上，任一步抛错整体回滚。</li>
 *   <li>{@code MOD1 ②} 不得写改 {@code originType}：卡表的 UPDATE 语句由
 *       {@link #CARD_UPDATE_SQL} 这一个常量承载，且 {@link #assertNoOriginTypeWrite()} 在类加载时
 *       就断言它不含该列名。审核路径上没有第二条改卡表的 SQL。</li>
 * </ul>
 */
@Slf4j
public final class PgModerationStore implements ModerationStore {

    /**
     * 🔴 审核处置唯一的卡表写语句。
     *
     * <p>{@code "reviewedAt" = COALESCE("reviewedAt", ?)} 是「只写一次」的落库形态：
     * 已有值时 COALESCE 取旧值，等于没改（也就不会触发禁改触发器）；为 NULL 时才写入新值。
     * 比"先查再判断要不要写"可靠——后者在并发下两个请求都可能读到 NULL。</p>
     *
     * <p>{@code AND "status" = ?} 是 CAS：另一个审核员抢先处置过则影响行数为 0，整体不生效。</p>
     *
     * <p>🔴 列清单里<b>没有</b> {@code originType} / {@code assistedByOps}，这是刻意的，见类注释。</p>
     */
    private static final String CARD_UPDATE_SQL = """
            UPDATE "t_memory_card"
               SET "status" = ?,
                   "visibility" = ?,
                   "updatedAt" = ?,
                   "reviewedAt" = CASE WHEN ? THEN COALESCE("reviewedAt", ?) ELSE "reviewedAt" END,
                   "pinnedAt" = CASE WHEN ? = 'public' AND ? = 'public'
                                     THEN "pinnedAt" ELSE NULL END
             WHERE "id" = ? AND "status" = ? AND "deletedAt" IS NULL
            """;

    private static final String CARD_COLUMNS = """
            "id","ownerId","petId","sourceType","sourceRef","coverKey","title","body",
            "topicIds","visibility","status","interaction","createdAt","updatedAt","publishedAt",
            "reviewedAt","pinnedAt","deletedAt","deletedBy","deleteReason","originType",
            "assistedByOps"
            """;

    private static final String TICKET_COLUMNS = """
            "id","cardId","submitBy","autoRiskLevel","autoSignals","state","handledBy",
            "reasonCode","note","snapshot","createdAt","handledAt",
            "appealText","appealAt","appealHandledBy","appealHandledAt","appealResult"
            """;

    static {
        assertNoOriginTypeWrite();
    }

    /**
     * 🔴 构建期自检：审核路径的卡表写语句不得触及 {@code originType}。
     *
     * <p>为什么要在代码里断言而不是只写注释：官方号内容不进北极星分母靠的是正向白名单
     * {@code originType='user'}。审核台一旦能改这个字段，就等于开放了一个<b>静默改分母</b>的入口，
     * 而流水上看起来只是一次正常审核——这种失效在报表上看不出来。后人往这条 SQL 里顺手加一列
     * 时，这个断言会让服务起不来，而不是等到季度复盘才发现北极星一直是错的。</p>
     */
    private static void assertNoOriginTypeWrite() {
        String sql = CARD_UPDATE_SQL.toLowerCase();
        if (sql.contains("origintype") || sql.contains("assistedbyops")) {
            throw new IllegalStateException(
                    "MOD1 ② 违规：审核处置的卡表 UPDATE 不得写 originType/assistedByOps —— "
                    + "官方号隔离靠正向白名单 originType='user'，审核台能改它就等于开放静默改北极星分母的入口。"
                    + "来源变更请走运营纠正流程（t_card_visibility_log 留痕 + §2.7 约定 9 重算），不是审核动作。");
        }
    }

    private final PgDb db;
    private final IDGenerator idGenerator;

    public PgModerationStore(PgDb db, IDGenerator idGenerator) {
        this.db = db;
        this.idGenerator = idGenerator;
    }

    // ------------------------------------------------------------------ 读

    @Override
    public MemoryCard card(long cardId) {
        String sql = "SELECT " + CARD_COLUMNS + " FROM \"t_memory_card\" WHERE \"id\" = ?";
        return queryOne(sql, ps -> ps.setLong(1, cardId), PgModerationStore::readCard);
    }

    @Override
    public ModerationTicket ticket(long moderationId) {
        String sql = "SELECT " + TICKET_COLUMNS + " FROM \"t_moderation\" WHERE \"id\" = ?";
        return queryOne(sql, ps -> ps.setLong(1, moderationId), PgModerationStore::readTicket);
    }

    @Override
    public ModerationTicket ticketOfCard(long cardId) {
        String sql = "SELECT " + TICKET_COLUMNS + " FROM \"t_moderation\""
                + " WHERE \"cardId\" = ? ORDER BY \"createdAt\" DESC, \"id\" DESC LIMIT 1";
        return queryOne(sql, ps -> ps.setLong(1, cardId), PgModerationStore::readTicket);
    }

    @Override
    public List<ModerationTicket> queue(String tab, String risk, long cursor, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(TICKET_COLUMNS)
                .append(" FROM \"t_moderation\" m WHERE EXISTS (")
                // 🔴 软删卡根本不应出现在队列里（§2.2.1）
                .append("SELECT 1 FROM \"t_memory_card\" c WHERE c.\"id\" = m.\"cardId\"")
                .append(" AND c.\"deletedAt\" IS NULL AND c.\"status\" <> 'deleted')");
        List<Object> args = new ArrayList<>();
        if (tab != null && !tab.isBlank()) {
            if ("handled".equals(tab)) {
                sql.append(" AND m.\"handledAt\" IS NOT NULL");
            } else {
                sql.append(" AND m.\"state\" = ?");
                args.add(tab);
            }
        }
        if (risk != null && !risk.isBlank()) {
            sql.append(" AND m.\"autoRiskLevel\" = ?");
            args.add(risk);
        }
        if (cursor > 0) {
            sql.append(" AND m.\"id\" > ?");
            args.add(cursor);
        }
        // 待处理 + 高风险优先（§4.3）
        sql.append(" ORDER BY CASE m.\"autoRiskLevel\" WHEN 'high' THEN 0 WHEN 'mid' THEN 1 ELSE 2 END,")
                .append(" m.\"createdAt\", m.\"id\" LIMIT ?");
        args.add(limit);
        return queryList(sql.toString(), args, PgModerationStore::readTicket);
    }

    @Override
    public List<CardVisibilityLog> historyOfCard(long cardId) {
        String sql = """
                SELECT "id","cardId","fromVisibility","toVisibility","fromStatus","toStatus",
                       "changedBy","changedRole","reasonCode","changedAt"
                  FROM "t_card_visibility_log" WHERE "cardId" = ? ORDER BY "changedAt", "id"
                """;
        return queryList(sql, List.of(cardId), rs -> {
            CardVisibilityLog l = new CardVisibilityLog();
            l.id = rs.getLong("id");
            l.cardId = rs.getLong("cardId");
            l.fromVisibility = rs.getString("fromVisibility");
            l.toVisibility = rs.getString("toVisibility");
            l.fromStatus = rs.getString("fromStatus");
            l.toStatus = rs.getString("toStatus");
            l.changedBy = rs.getLong("changedBy");
            l.changedRole = rs.getString("changedRole");
            l.reasonCode = rs.getString("reasonCode");
            l.changedAt = rs.getLong("changedAt");
            return l;
        });
    }

    @Override
    public List<Report> reports(String status, long cardId, long cursor, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT "id","cardId","reporterId","reasonCode","note","status","createdAt","handledAt"
                  FROM "t_report" WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND \"status\" = ?");
            args.add(status);
        }
        if (cardId > 0) {
            sql.append(" AND \"cardId\" = ?");
            args.add(cardId);
        }
        if (cursor > 0) {
            sql.append(" AND \"id\" > ?");
            args.add(cursor);
        }
        sql.append(" ORDER BY \"createdAt\", \"id\" LIMIT ?");
        args.add(limit);
        return queryList(sql.toString(), args, rs -> {
            Report r = new Report();
            r.id = rs.getLong("id");
            r.cardId = rs.getLong("cardId");
            r.reporterId = rs.getLong("reporterId");
            r.reasonCode = rs.getString("reasonCode");
            r.note = rs.getString("note");
            r.status = rs.getString("status");
            r.createdAt = rs.getLong("createdAt");
            r.handledAt = getNullableLong(rs, "handledAt");
            return r;
        });
    }

    @Override
    public ModerationSetting setting() {
        String sql = "SELECT \"mode\",\"scopeJson\",\"updatedBy\",\"updatedAt\""
                + " FROM \"t_moderation_setting\" WHERE \"id\" = 1";
        ModerationSetting s = queryOne(sql, ps -> { }, rs -> {
            ModerationSetting v = new ModerationSetting();
            v.mode = rs.getString("mode");
            v.scopeJson = rs.getString("scopeJson");
            v.updatedBy = rs.getLong("updatedBy");
            v.updatedAt = rs.getLong("updatedAt");
            return v;
        });
        return s != null ? s : new ModerationSetting();
    }

    // ------------------------------------------------------------------ 写（事务）

    @Override
    public HandleResult handleAtomically(HandleCommand cmd) {
        try {
            return db.inTransaction(conn -> {
                // ① 卡状态 + reviewedAt（CAS + 只写一次）
                int affected = updateCard(conn, cmd);
                if (affected == 0) {
                    // CAS 未命中：当前状态已不是期望值，整体不生效
                    return null;
                }
                // ② 工单
                updateTicket(conn, cmd);
                // ③④ 双流水（🔴 与 ①② 同一事务）
                insertVisibilityLog(conn, cmd);
                insertAuditLog(conn, cmd);
                return readResult(conn, cmd);
            });
        } catch (SQLException e) {
            throw wrap(e, "审核处置");
        }
    }

    @Override
    public HandleResult appealAtomically(HandleCommand cmd, String appealText) {
        try {
            return db.inTransaction(conn -> {
                // 🔴 「一生一次」写在 WHERE 里：appealAt IS NULL 才写得进去。
                //    不是"先查再写"——那中间有竞态窗口，两个并发请求都会读到"还没申诉过"。
                String sql = """
                        UPDATE "t_moderation"
                           SET "appealAt" = ?, "appealText" = ?, "state" = ?
                         WHERE "id" = ? AND "appealAt" IS NULL
                        """;
                int affected;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, cmd.now);
                    ps.setString(2, appealText);
                    ps.setString(3, cmd.toModerationState);
                    ps.setLong(4, cmd.moderationId);
                    affected = ps.executeUpdate();
                }
                if (affected == 0) {
                    return null;   // 机会已用掉
                }
                if (updateCard(conn, cmd) == 0) {
                    return null;   // CAS 未命中 → 连同上面的 appealAt 一起回滚
                }
                insertVisibilityLog(conn, cmd);
                insertAuditLog(conn, cmd);
                return readResult(conn, cmd);
            });
        } catch (SQLException e) {
            throw wrap(e, "申诉提交");
        }
    }

    private int updateCard(Connection conn, HandleCommand cmd) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(CARD_UPDATE_SQL)) {
            ps.setString(1, cmd.toCardStatus);
            ps.setString(2, cmd.toVisibility);
            ps.setLong(3, cmd.now);
            ps.setBoolean(4, cmd.writeReviewedAt);
            ps.setLong(5, cmd.now);
            // 🔴 自动解除置顶：目标状态或可见性只要不是 public，pinnedAt 就在同一条语句里清空。
            //    分两步会留下「已经不公开、但还置顶着」的卡，它下次过审时自己跳回置顶位。
            ps.setString(6, cmd.toCardStatus);
            ps.setString(7, cmd.toVisibility);
            ps.setLong(8, cmd.cardId);
            ps.setString(9, cmd.expectedCardStatus);
            return ps.executeUpdate();
        }
    }

    private void updateTicket(Connection conn, HandleCommand cmd) throws SQLException {
        // 🔴 申诉处置不碰 appealAt（overturn 不等于退还申诉机会）；数据库触发器也会拒绝改它
        String sql = cmd.appealResult != null ? """
                UPDATE "t_moderation"
                   SET "state" = ?, "handledAt" = ?, "reasonCode" = COALESCE(?, "reasonCode"),
                       "note" = COALESCE(?, "note"), "snapshot" = COALESCE(?::jsonb, "snapshot"),
                       "appealHandledBy" = ?, "appealHandledAt" = ?, "appealResult" = ?
                 WHERE "id" = ?
                """ : """
                UPDATE "t_moderation"
                   SET "state" = ?, "handledAt" = ?, "reasonCode" = COALESCE(?, "reasonCode"),
                       "note" = COALESCE(?, "note"), "snapshot" = COALESCE(?::jsonb, "snapshot"),
                       "handledBy" = ?
                 WHERE "id" = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, cmd.toModerationState);
            ps.setLong(i++, cmd.now);
            ps.setString(i++, cmd.reasonCode);
            ps.setString(i++, cmd.note);
            ps.setString(i++, cmd.snapshotJson);
            if (cmd.appealResult != null) {
                ps.setLong(i++, cmd.operatorId);
                ps.setLong(i++, cmd.now);
                ps.setString(i++, cmd.appealResult);
            } else {
                ps.setLong(i++, cmd.operatorId);
            }
            ps.setLong(i, cmd.moderationId);
            ps.executeUpdate();
        }
    }

    private void insertVisibilityLog(Connection conn, HandleCommand cmd) throws SQLException {
        String sql = """
                INSERT INTO "t_card_visibility_log"
                    ("id","cardId","fromVisibility","toVisibility","fromStatus","toStatus",
                     "changedBy","changedRole","reasonCode","changedAt")
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, idGenerator.nextId());
            ps.setLong(2, cmd.cardId);
            ps.setString(3, cmd.fromVisibility);
            ps.setString(4, cmd.toVisibility);
            ps.setString(5, cmd.expectedCardStatus);
            ps.setString(6, cmd.toCardStatus);
            ps.setLong(7, cmd.operatorId);
            ps.setString(8, cmd.changedRole);
            ps.setString(9, cmd.reasonCode);
            ps.setLong(10, cmd.now);
            ps.executeUpdate();
        }
    }

    private void insertAuditLog(Connection conn, HandleCommand cmd) throws SQLException {
        String sql = """
                INSERT INTO "t_audit_log"
                    ("id","actor","actorType","action","targetType","targetId","scope","ts")
                VALUES (?,?,?,?,?,?,?::jsonb,?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, idGenerator.nextId());
            ps.setString(2, String.valueOf(cmd.operatorId));
            ps.setString(3, cmd.auditActorType);
            ps.setString(4, cmd.auditAction);
            ps.setString(5, "card");
            ps.setString(6, String.valueOf(cmd.cardId));
            ps.setString(7, cmd.auditScopeJson);
            ps.setLong(8, cmd.now);
            ps.executeUpdate();
        }
    }

    /** 事务内回读，保证出参回显的是刚提交的那份事实（尤其 reviewedAt 的"只写一次"结果）。 */
    private HandleResult readResult(Connection conn, HandleCommand cmd) throws SQLException {
        String sql = """
                SELECT c."status" AS "cardStatus", c."reviewedAt" AS "reviewedAt",
                       m."state" AS "state", m."handledAt" AS "handledAt"
                  FROM "t_memory_card" c JOIN "t_moderation" m ON m."id" = ?
                 WHERE c."id" = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cmd.moderationId);
            ps.setLong(2, cmd.cardId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                HandleResult r = new HandleResult();
                r.moderationId = cmd.moderationId;
                r.cardId = cmd.cardId;
                r.state = rs.getString("state");
                r.cardStatus = rs.getString("cardStatus");
                r.reviewedAt = getNullableLong(rs, "reviewedAt");
                Long handledAt = getNullableLong(rs, "handledAt");
                r.handledAt = handledAt != null ? handledAt : cmd.now;
                return r;
            }
        }
    }

    @Override
    public ModerationSetting updateSetting(String mode, String scopeJson, long operatorId, long now) {
        try {
            return db.inTransaction(conn -> {
                String sql = """
                        UPDATE "t_moderation_setting"
                           SET "mode" = ?, "scopeJson" = ?::jsonb, "updatedBy" = ?, "updatedAt" = ?
                         WHERE "id" = 1
                        """;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, mode);
                    ps.setString(2, scopeJson);
                    ps.setLong(3, operatorId);
                    ps.setLong(4, now);
                    ps.executeUpdate();
                }
                String auditSql = """
                        INSERT INTO "t_audit_log"
                            ("id","actor","actorType","action","targetType","targetId","scope","ts")
                        VALUES (?,?,?,?,?,?,?::jsonb,?)
                        """;
                try (PreparedStatement ps = conn.prepareStatement(auditSql)) {
                    ps.setLong(1, idGenerator.nextId());
                    ps.setString(2, String.valueOf(operatorId));
                    ps.setString(3, "staff");
                    ps.setString(4, AuditAction.SETTINGS_UPDATE);
                    ps.setString(5, "config");
                    ps.setString(6, "moderation.settings");
                    ps.setString(7, "{\"mode\":\"" + mode + "\"}");
                    ps.setLong(8, now);
                    ps.executeUpdate();
                }
                ModerationSetting s = new ModerationSetting();
                s.mode = mode;
                s.scopeJson = scopeJson;
                s.updatedBy = operatorId;
                s.updatedAt = now;
                return s;
            });
        } catch (SQLException e) {
            throw wrap(e, "切换先审后发开关");
        }
    }

    // ------------------------------------------------------------------ 造数

    @Override
    public void putCard(MemoryCard card) {
        // 🔴 originType 在发布路径显式落库（无默认值 + CHECK）。这里是发布路径的最小替身，
        //    同样要求调用方显式给出——漏写则数据库 NOT NULL 报错，这正是闸门 G-1 想要的行为。
        String sql = """
                INSERT INTO "t_memory_card"
                    ("id","ownerId","petId","sourceType","sourceRef","coverKey","title","body",
                     "topicIds","visibility","status","interaction","createdAt","updatedAt",
                     "publishedAt","originType","assistedByOps")
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?,?::jsonb,?,?,?,?,?)
                ON CONFLICT ("id") DO NOTHING
                """;
        try {
            db.update(sql, ps -> {
                ps.setLong(1, card.id);
                ps.setLong(2, card.ownerId);
                ps.setLong(3, card.petId);
                ps.setString(4, card.sourceType);
                ps.setString(5, card.sourceRef);
                ps.setString(6, card.coverKey);
                ps.setString(7, card.title);
                ps.setString(8, card.body);
                ps.setString(9, card.topicIdsJson);
                ps.setString(10, card.visibilityIntent);
                ps.setString(11, card.status);
                ps.setString(12, card.interactionJson);
                ps.setLong(13, card.createdAt);
                ps.setLong(14, card.updatedAt);
                setNullableLong(ps, 15, card.publishedAt);
                ps.setString(16, card.originType);
                ps.setBoolean(17, card.assistedByOps);
            });
        } catch (SQLException e) {
            throw wrap(e, "落回忆卡");
        }
    }

    @Override
    public List<MemoryCard> publicCards(int limit) {
        // 🔴 只按状态过滤。可见性的「卡不得宽于窗」是读时取交集，窗不在本表里，
        //    强行 join 会让约束分散到两处，改一处忘一处的后果是内容泄漏。
        String sql = "SELECT " + CARD_COLUMNS + " FROM \"t_memory_card\""
                + " WHERE \"status\" = 'public' AND \"deletedAt\" IS NULL"
                // id 兜底保证顺序稳定：publishedAt 相同的两张卡若顺序抖动，分页会重复或漏发
                + " ORDER BY \"publishedAt\" DESC NULLS LAST, \"id\" DESC LIMIT ?";
        return queryList(sql, List.of(Math.max(0, limit)), PgModerationStore::readCard);
    }

    @Override
    public List<MemoryCard> cardsOfOwner(long ownerId, int limit) {
        // 🔴 ORDER BY 与 PinPolicy.ORDER 必须逐字对应，否则内存态联调与落库跑出来的
        //    顺序不同，而那种不一致只在有人置顶之后才显形。
        String sql = "SELECT " + CARD_COLUMNS + " FROM \"t_memory_card\""
                + " WHERE \"ownerId\" = ? AND \"deletedAt\" IS NULL"
                + " ORDER BY \"pinnedAt\" DESC NULLS LAST, \"publishedAt\" DESC NULLS LAST,"
                + " \"id\" DESC LIMIT ?";
        return queryList(sql, List.of(ownerId, Math.max(0, limit)), PgModerationStore::readCard);
    }

    @Override
    public int countPinned(long ownerId) {
        String sql = "SELECT count(*) FROM \"t_memory_card\""
                + " WHERE \"ownerId\" = ? AND \"pinnedAt\" IS NOT NULL AND \"deletedAt\" IS NULL";
        Integer n = queryOne(sql, ps -> ps.setLong(1, ownerId), rs -> rs.getInt(1));
        return n == null ? 0 : n;
    }

    @Override
    public boolean changeVisibilityAtomically(long cardId, long ownerId, String expectedVisibility,
                                              String toVisibility, boolean clearPin, long now) {
        try {
            return db.inTransaction(conn -> {
                // 🔴 窄 UPDATE：status / reviewedAt / originType 一个都不在语句里。
                //    作者改可见性不是审核动作，不得成为改这些字段的旁路。
                //    WHERE 带 ownerId + visibility 做 CAS：既是归属校验，也避免并发下
                //    两个请求各写一条流水而其中一条的 fromVisibility 是错的（流水只追加，改不回来）。
                String sql = """
                        UPDATE "t_memory_card"
                           SET "visibility" = ?, "updatedAt" = ?,
                               "pinnedAt" = CASE WHEN ? THEN NULL ELSE "pinnedAt" END
                         WHERE "id" = ? AND "ownerId" = ? AND "visibility" = ?
                           AND "deletedAt" IS NULL
                        """;
                int affected;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, toVisibility);
                    ps.setLong(2, now);
                    ps.setBoolean(3, clearPin);
                    ps.setLong(4, cardId);
                    ps.setLong(5, ownerId);
                    ps.setString(6, expectedVisibility);
                    affected = ps.executeUpdate();
                }
                if (affected == 0) {
                    return false;
                }
                // 作者改可见性不改状态，流水两端状态同值（靠这个区分「作者收回」与「运营下架」）
                String status = queryStatusInTx(conn, cardId);
                insertAuthorVisibilityLog(conn, cardId, expectedVisibility, toVisibility,
                        status, ownerId, now);
                return true;
            });
        } catch (SQLException e) {
            throw wrap(e, "作者更新卡可见性");
        }
    }

    @Override
    public PinOutcome setPinnedAtomically(long cardId, long ownerId, boolean pin,
                                          int maxPinned, long now) {
        try {
            return db.inTransaction(conn -> {
                if (!pin) {
                    String sql = "UPDATE \"t_memory_card\" SET \"pinnedAt\" = NULL,"
                            + " \"updatedAt\" = ? WHERE \"id\" = ? AND \"ownerId\" = ?"
                            + " AND \"deletedAt\" IS NULL";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setLong(1, now);
                        ps.setLong(2, cardId);
                        ps.setLong(3, ownerId);
                        return ps.executeUpdate() > 0 ? PinOutcome.OK : PinOutcome.NOT_FOUND;
                    }
                }

                // 🔴 上限校验与写入在同一事务：作者同时点两张卡时，先查后写会让两个请求
                //    都读到「还能再置 1 张」然后都写进去。SELECT ... FOR UPDATE 锁住该作者
                //    已置顶的那几行，把并发串起来。
                int pinned;
                String countSql = "SELECT count(*) FROM (SELECT 1 FROM \"t_memory_card\""
                        + " WHERE \"ownerId\" = ? AND \"pinnedAt\" IS NOT NULL"
                        + " AND \"deletedAt\" IS NULL FOR UPDATE) t";
                try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                    ps.setLong(1, ownerId);
                    try (ResultSet rs = ps.executeQuery()) {
                        pinned = rs.next() ? rs.getInt(1) : 0;
                    }
                }

                // 🔴 仅 public 且已过审可置顶。reviewedAt 非空还不够——它只写一次、写后不可变，
                //    所以被下架的卡它也非空；不判 status 会让下架的卡还能被置顶。
                //    幂等：已置顶的再置一次直接成功，不占额度（否则重复点击会撞上限）。
                String sql = """
                        UPDATE "t_memory_card" SET "pinnedAt" = ?, "updatedAt" = ?
                         WHERE "id" = ? AND "ownerId" = ? AND "deletedAt" IS NULL
                           AND "status" = 'public' AND "visibility" = 'public'
                           AND "reviewedAt" IS NOT NULL
                           AND ("pinnedAt" IS NOT NULL OR ? > 0)
                        """;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, now);
                    ps.setLong(2, now);
                    ps.setLong(3, cardId);
                    ps.setLong(4, ownerId);
                    ps.setInt(5, maxPinned - pinned);
                    if (ps.executeUpdate() > 0) {
                        return PinOutcome.OK;
                    }
                }
                // 没改到行：要么额度满了，要么卡不满足条件。分开回，两者给用户的话不同
                return pinned >= maxPinned ? PinOutcome.AT_CAPACITY : PinOutcome.NOT_FOUND;
            });
        } catch (SQLException e) {
            throw wrap(e, "置顶回忆卡");
        }
    }

    private String queryStatusInTx(Connection conn, long cardId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT \"status\" FROM \"t_memory_card\" WHERE \"id\" = ?")) {
            ps.setLong(1, cardId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 作者自改可见性的流水。{@code changedRole='author'}（白名单本来就有这一档）。 */
    private void insertAuthorVisibilityLog(Connection conn, long cardId, String from, String to,
                                           String status, long ownerId, long now)
            throws SQLException {
        String sql = """
                INSERT INTO "t_card_visibility_log"
                    ("id","cardId","fromVisibility","toVisibility","fromStatus","toStatus",
                     "changedBy","changedRole","reasonCode","changedAt")
                VALUES (?,?,?,?,?,?,?,'author',NULL,?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, idGenerator.nextId());
            ps.setLong(2, cardId);
            ps.setString(3, from);
            ps.setString(4, to);
            ps.setString(5, status);
            ps.setString(6, status);
            ps.setLong(7, ownerId);
            ps.setLong(8, now);
            ps.executeUpdate();
        }
    }

    @Override
    public boolean updateInteraction(long cardId, String interactionJson, long now) {
        // 🔴 只有 interaction 与 updatedAt 两列。originType / reviewedAt / status / visibility
        //    一个都不在语句里 —— 作者关互动不是审核动作，不得成为改这些字段的旁路。
        String sql = "UPDATE \"t_memory_card\" SET \"interaction\"=?::jsonb,\"updatedAt\"=?"
                + " WHERE \"id\"=? AND \"deletedAt\" IS NULL";
        try {
            return db.update(sql, ps -> {
                ps.setString(1, interactionJson);
                ps.setLong(2, now);
                ps.setLong(3, cardId);
            }) > 0;
        } catch (SQLException e) {
            throw wrap(e, "更新互动开关");
        }
    }

    @Override
    public void putTicket(ModerationTicket t) {
        String sql = """
                INSERT INTO "t_moderation"
                    ("id","cardId","submitBy","autoRiskLevel","autoSignals","state",
                     "note","snapshot","createdAt")
                VALUES (?,?,?,?,?::jsonb,?,?,?::jsonb,?)
                ON CONFLICT ("id") DO NOTHING
                """;
        try {
            db.update(sql, ps -> {
                ps.setLong(1, t.id);
                ps.setLong(2, t.cardId);
                ps.setLong(3, t.submitBy);
                ps.setString(4, t.autoRiskLevel);
                ps.setString(5, t.autoSignalsJson);
                ps.setString(6, t.state);
                ps.setString(7, t.note);
                ps.setString(8, t.snapshotJson);
                ps.setLong(9, t.createdAt);
            });
        } catch (SQLException e) {
            throw wrap(e, "落审核工单");
        }
    }

    @Override
    public void putReport(Report r) {
        String sql = """
                INSERT INTO "t_report"
                    ("id","cardId","reporterId","reasonCode","note","status","createdAt")
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT ("id") DO NOTHING
                """;
        try {
            db.update(sql, ps -> {
                ps.setLong(1, r.id);
                ps.setLong(2, r.cardId);
                ps.setLong(3, r.reporterId);
                ps.setString(4, r.reasonCode);
                ps.setString(5, r.note);
                ps.setString(6, r.status);
                ps.setLong(7, r.createdAt);
            });
        } catch (SQLException e) {
            throw wrap(e, "落举报");
        }
    }

    @Override
    public List<AuditLog> auditLogs(String targetType, String targetId) {
        StringBuilder sql = new StringBuilder("""
                SELECT "id","actor","actorType","action","targetType","targetId",
                       "scope"::text AS "scope","ts","ip","reviewerId"
                  FROM "t_audit_log" WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (targetType != null) {
            sql.append(" AND \"targetType\" = ?");
            args.add(targetType);
        }
        if (targetId != null) {
            sql.append(" AND \"targetId\" = ?");
            args.add(targetId);
        }
        sql.append(" ORDER BY \"ts\", \"id\"");
        return queryList(sql.toString(), args, rs -> {
            AuditLog a = new AuditLog();
            a.id = rs.getLong("id");
            a.actor = rs.getString("actor");
            a.actorType = rs.getString("actorType");
            a.action = rs.getString("action");
            a.targetType = rs.getString("targetType");
            a.targetId = rs.getString("targetId");
            a.scopeJson = rs.getString("scope");
            a.ts = rs.getLong("ts");
            a.ip = rs.getString("ip");
            a.reviewerId = rs.getString("reviewerId");
            return a;
        });
    }

    // ------------------------------------------------------------------ 行映射与工具

    private static MemoryCard readCard(ResultSet rs) throws SQLException {
        MemoryCard c = new MemoryCard();
        c.id = rs.getLong("id");
        c.ownerId = rs.getLong("ownerId");
        c.petId = rs.getLong("petId");
        c.sourceType = rs.getString("sourceType");
        c.sourceRef = rs.getString("sourceRef");
        c.coverKey = rs.getString("coverKey");
        c.title = rs.getString("title");
        c.body = rs.getString("body");
        c.topicIdsJson = rs.getString("topicIds");
        c.visibilityIntent = rs.getString("visibility");
        c.status = rs.getString("status");
        c.interactionJson = rs.getString("interaction");
        c.createdAt = rs.getLong("createdAt");
        c.updatedAt = rs.getLong("updatedAt");
        c.publishedAt = getNullableLong(rs, "publishedAt");
        c.reviewedAt = getNullableLong(rs, "reviewedAt");
        c.pinnedAt = getNullableLong(rs, "pinnedAt");
        c.deletedAt = getNullableLong(rs, "deletedAt");
        c.deletedBy = getNullableLong(rs, "deletedBy");
        c.deleteReason = rs.getString("deleteReason");
        c.originType = rs.getString("originType");
        c.assistedByOps = rs.getBoolean("assistedByOps");
        return c;
    }

    private static ModerationTicket readTicket(ResultSet rs) throws SQLException {
        ModerationTicket t = new ModerationTicket();
        t.id = rs.getLong("id");
        t.cardId = rs.getLong("cardId");
        t.submitBy = rs.getLong("submitBy");
        t.autoRiskLevel = rs.getString("autoRiskLevel");
        t.autoSignalsJson = rs.getString("autoSignals");
        t.state = rs.getString("state");
        t.handledBy = getNullableLong(rs, "handledBy");
        t.reasonCode = rs.getString("reasonCode");
        t.note = rs.getString("note");
        t.snapshotJson = rs.getString("snapshot");
        t.createdAt = rs.getLong("createdAt");
        t.handledAt = getNullableLong(rs, "handledAt");
        t.appealText = rs.getString("appealText");
        t.appealAt = getNullableLong(rs, "appealAt");
        t.appealHandledBy = getNullableLong(rs, "appealHandledBy");
        t.appealHandledAt = getNullableLong(rs, "appealHandledAt");
        t.appealResult = rs.getString("appealResult");
        return t;
    }

    private static Long getNullableLong(ResultSet rs, String label) throws SQLException {
        long v = rs.getLong(label);
        return rs.wasNull() ? null : v;
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, v);
        }
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private <T> T queryOne(String sql, Binder binder, RowMapper<T> mapper) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapper.map(rs) : null;
            }
        } catch (SQLException e) {
            throw wrap(e, "查询");
        }
    }

    private <T> List<T> queryList(String sql, List<Object> args, RowMapper<T> mapper) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw wrap(e, "查询");
        }
    }

    private static RuntimeException wrap(SQLException e, String what) {
        log.error("{}失败", what, e);
        return new com.echo.http.ApiException(com.echo.http.ApiException.SERVER_ERROR,
                "这里出了点小状况，待会儿再来看看它好吗？", what + " failed: " + e.getMessage());
    }

    /** 便于测试直接断言"队列里不该出现软删卡"的语义（不含分页）。 */
    boolean isCardVisibleToQueue(MemoryCard c) {
        return c != null && c.deletedAt == null && !CardStatus.DELETED.equals(c.status);
    }
}
