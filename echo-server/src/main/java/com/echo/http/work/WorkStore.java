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

/**
 * 作品存储（{@code t_work}）。无 DB 时退化为内存态，语义一致
 * （与 {@code FollowStore} / {@code BlockStore} 同构）。
 *
 * <h2>🔴 一切「取列表」都必须带 deletedAt IS NULL</h2>
 *
 * <p>本表软删（G0-1），删除只是写三列。<b>漏掉这个条件不会报错，只会让删掉的作品
 * 重新出现在广场上</b>——而作者已经以为它没了。所以本类<b>不提供</b>任何
 * 「查全部行」的方法，读路径一律从下面几个带谓词的方法里走。</p>
 *
 * <h2>为什么读走 SQL 而不是像 FollowStore 那样全量装进内存</h2>
 *
 * <p>关注关系是稀疏的小对象，装得下；作品会长到十万量级且带正文，装不下。
 * 所以本类只在<b>无 DB</b> 时用内存 Map，有 DB 时每次查库。</p>
 */
@Slf4j
public final class WorkStore {

    private static final String COLUMNS = "\"id\",\"authorId\",\"sourceCardId\",\"mediaType\","
            + "\"mediaKey\",\"posterKey\",\"durationMs\",\"width\",\"height\",\"title\",\"body\","
            + "\"topicIds\",\"visibility\",\"status\",\"originType\",\"aiGenerated\","
            + "\"createdAt\",\"updatedAt\",\"publishedAt\",\"reviewedAt\","
            + "\"deletedAt\",\"deletedBy\",\"deleteReason\"";

    private final PgDb db;
    /** 内存态兜底：id -> 作品。仅在 {@code db == null} 时使用。 */
    private final Map<Long, Work> memory = new ConcurrentHashMap<>();

    public WorkStore(PgDb db) {
        this.db = db;
    }

    private boolean persistent() {
        return db != null;
    }

    // ------------------------------------------------------------------ 写

    /** 落一条作品。返回是否成功。 */
    public boolean insert(Work w) {
        if (!persistent()) {
            memory.put(w.id, w);
            return true;
        }
        String sql = "INSERT INTO \"t_work\" (" + COLUMNS + ") VALUES ("
                + "?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, w.id);
            ps.setLong(i++, w.authorId);
            setNullableLong(ps, i++, w.sourceCardId);
            ps.setString(i++, w.mediaType);
            ps.setString(i++, w.mediaKey);
            ps.setString(i++, w.posterKey);
            ps.setInt(i++, w.durationMs);
            ps.setInt(i++, w.width);
            ps.setInt(i++, w.height);
            ps.setString(i++, w.title);
            ps.setString(i++, w.body);
            ps.setString(i++, w.topicIdsJson == null ? "[]" : w.topicIdsJson);
            ps.setString(i++, w.visibility);
            ps.setString(i++, w.status);
            ps.setString(i++, w.originType);
            ps.setBoolean(i++, w.aiGenerated);
            ps.setLong(i++, w.createdAt);
            ps.setLong(i++, w.updatedAt);
            setNullableLong(ps, i++, w.publishedAt);
            setNullableLong(ps, i++, w.reviewedAt);
            setNullableLong(ps, i++, w.deletedAt);
            setNullableLong(ps, i++, w.deletedBy);
            ps.setString(i, w.deleteReason);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            // 唯一索引冲突（同一张回忆卡重复发布）在这里落地。调用方靠返回 false 判，
            // 🔴 不要把异常往上抛成 500：那是用户重复点了一下发布，不是服务器坏了。
            log.warn("插入作品失败 id={} sourceCardId={}: {}", w.id, w.sourceCardId, e.getMessage());
            return false;
        }
    }

    /**
     * 软删（G0-1）。🔴 本类<b>不提供</b>物理删除方法，别加。
     */
    public boolean softDelete(long id, long operatorId, String reason, long now) {
        if (!persistent()) {
            Work w = memory.get(id);
            if (w == null || w.isDeleted()) {
                return false;
            }
            w.deletedAt = now;
            w.deletedBy = operatorId;
            w.deleteReason = reason;
            w.status = Work.Status.DELETED;
            w.updatedAt = now;
            return true;
        }
        String sql = "UPDATE \"t_work\" SET \"deletedAt\"=?,\"deletedBy\"=?,\"deleteReason\"=?,"
                + "\"status\"='deleted',\"updatedAt\"=? WHERE \"id\"=? AND \"deletedAt\" IS NULL";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, operatorId);
            ps.setString(3, reason);
            ps.setLong(4, now);
            ps.setLong(5, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("软删作品失败 id={}: {}", id, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ 读

    /** 单条。已软删的返回 {@code null}。 */
    public Work byId(long id) {
        if (!persistent()) {
            Work w = memory.get(id);
            return w == null || w.isDeleted() ? null : w;
        }
        List<Work> got = query("SELECT " + COLUMNS + " FROM \"t_work\""
                + " WHERE \"id\"=? AND \"deletedAt\" IS NULL", List.of(id));
        return got.isEmpty() ? null : got.get(0);
    }

    /**
     * 作品瀑布：全站已公开的作品，按发布时间倒序。
     *
     * <p>🔴 只按 {@code status} 过滤，<b>不在此处判拉黑与可见性</b>——那两者要看
     * 观看者是谁，属于应用层。理由与 {@code PgModerationStore.publicCards} 一致：
     * 把可见性拆到两处，改一处忘一处的后果是内容泄漏。</p>
     */
    public List<Work> publicWorks(int limit) {
        if (!persistent()) {
            return memory.values().stream()
                    .filter(w -> !w.isDeleted() && Work.Status.PUBLIC.equals(w.status))
                    .sorted(byPublishedDesc())
                    .limit(Math.max(0, limit))
                    .toList();
        }
        return query("SELECT " + COLUMNS + " FROM \"t_work\""
                + " WHERE \"status\"='public' AND \"deletedAt\" IS NULL"
                // id 兜底保证顺序稳定：publishedAt 相同的两条若顺序抖动，分页会重复或漏发
                + " ORDER BY \"publishedAt\" DESC NULLS LAST, \"id\" DESC LIMIT ?",
                List.of(Math.max(0, limit)));
    }

    /**
     * 个人作品页。
     *
     * @param includeUnpublished 作者看自己时为 {@code true}（草稿与待审也要看得见）；
     *                           🔴 陌生人看别人时<b>必须</b>为 {@code false}
     */
    public List<Work> worksOfAuthor(long authorId, boolean includeUnpublished, int limit) {
        if (!persistent()) {
            return memory.values().stream()
                    .filter(w -> !w.isDeleted() && w.authorId == authorId)
                    .filter(w -> includeUnpublished || Work.Status.PUBLIC.equals(w.status))
                    .sorted(byPublishedDesc())
                    .limit(Math.max(0, limit))
                    .toList();
        }
        String statusClause = includeUnpublished ? "" : " AND \"status\"='public'";
        return query("SELECT " + COLUMNS + " FROM \"t_work\""
                + " WHERE \"authorId\"=? AND \"deletedAt\" IS NULL" + statusClause
                + " ORDER BY \"publishedAt\" DESC NULLS LAST, \"id\" DESC LIMIT ?",
                List.of(authorId, Math.max(0, limit)));
    }

    /**
     * 这张回忆卡是否已经发布成作品。
     *
     * <p>发布页据此把按钮从「发布」换成「已发布」——🔴 不查这一下的后果不是重复入库
     * （唯一索引会拦），而是<b>用户点了发布却收到一个失败</b>，而他并不知道自己发过。</p>
     */
    public boolean publishedFromCard(long cardId) {
        if (!persistent()) {
            return memory.values().stream()
                    .anyMatch(w -> !w.isDeleted() && Long.valueOf(cardId).equals(w.sourceCardId));
        }
        return !query("SELECT " + COLUMNS + " FROM \"t_work\""
                + " WHERE \"sourceCardId\"=? AND \"deletedAt\" IS NULL LIMIT 1",
                List.of(cardId)).isEmpty();
    }

    // ------------------------------------------------------------------ 内部

    private static Comparator<Work> byPublishedDesc() {
        return Comparator.<Work, Long>comparing(w -> w.publishedAt == null ? 0L : w.publishedAt)
                .thenComparing(w -> w.id)
                .reversed();
    }

    private List<Work> query(String sql, List<Object> args) {
        List<Work> out = new ArrayList<>();
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
            log.warn("查询作品失败: {}", e.getMessage());
        }
        return out;
    }

    private static Work read(ResultSet rs) throws SQLException {
        Work w = new Work();
        w.id = rs.getLong("id");
        w.authorId = rs.getLong("authorId");
        w.sourceCardId = nullableLong(rs, "sourceCardId");
        w.mediaType = rs.getString("mediaType");
        w.mediaKey = orEmpty(rs.getString("mediaKey"));
        w.posterKey = orEmpty(rs.getString("posterKey"));
        w.durationMs = rs.getInt("durationMs");
        w.width = rs.getInt("width");
        w.height = rs.getInt("height");
        w.title = orEmpty(rs.getString("title"));
        w.body = orEmpty(rs.getString("body"));
        w.topicIdsJson = rs.getString("topicIds");
        w.visibility = rs.getString("visibility");
        w.status = rs.getString("status");
        w.originType = rs.getString("originType");
        w.aiGenerated = rs.getBoolean("aiGenerated");
        w.createdAt = rs.getLong("createdAt");
        w.updatedAt = rs.getLong("updatedAt");
        w.publishedAt = nullableLong(rs, "publishedAt");
        w.reviewedAt = nullableLong(rs, "reviewedAt");
        w.deletedAt = nullableLong(rs, "deletedAt");
        w.deletedBy = nullableLong(rs, "deletedBy");
        w.deleteReason = rs.getString("deleteReason");
        return w;
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

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
