package com.echo.http.work;

import com.echo.infra.persistence.PgDb;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * 素材归属（{@code t_resource}）。无 DB 时退化为内存态，语义一致。
 *
 * <h2>为什么会有这张表</h2>
 *
 * <p>{@code POST /upload} 此前只把 accountId 写进日志就扔了，<b>全库没有任何一张表
 * 知道某个 resourceId 是谁传的</b>。于是 {@code POST /works} 无从校验 mediaKey 归属——
 * 拿到别人的 key 就能把别人的照片发布成自己的作品（{@code SPEC-security §4.14 E4}）。</p>
 *
 * <p>它同时是「下架即不可取」的前置：{@code IStorage} 至今没有 delete 方法，
 * 软删只改数据库状态、字节永远留在盘上（同上 {@code E1}）。要清得先知道有哪些 key。</p>
 *
 * <h2>🔴 记不上不等于可以放行</h2>
 *
 * <p>写入失败时本类返回 false，调用方<b>必须</b>据此拒绝上传，不能「记不上就算了」——
 * 那等于给攻击者一条绕过归属校验的路：把写入打挂，后续所有 key 就都无主了。</p>
 */
@Slf4j
public final class ResourceStore {

    /** 内存态兜底：resourceId -> 归属账号。仅在 {@code db == null} 时使用。 */
    private final Map<String, Long> memoryOwner = new ConcurrentHashMap<>();
    private final Set<String> memoryCleanupQueue = ConcurrentHashMap.newKeySet();
    private final PgDb db;

    public ResourceStore(PgDb db) {
        this.db = db;
    }

    private boolean persistent() {
        return db != null;
    }

    /** 记一条素材归属。返回是否成功；失败时调用方必须拒绝本次上传。 */
    public boolean record(String resourceId, long ownerId, String storageKey,
                          String contentType, long bytes, long now) {
        if (resourceId == null || resourceId.isEmpty() || ownerId <= 0) {
            return false;
        }
        if (!persistent()) {
            Long existing = memoryOwner.putIfAbsent(resourceId, ownerId);
            boolean accepted = existing == null || existing == ownerId;
            if (accepted) memoryCleanupQueue.remove(resourceId);
            return accepted;
        }
        String sql = "INSERT INTO \"t_resource\"(\"resourceId\",\"ownerId\",\"storageKey\","
                + "\"contentType\",\"bytes\",\"createdAt\") VALUES(?,?,?,?,?,?) "
                + "ON CONFLICT (\"resourceId\") DO UPDATE SET "
                + "\"storageKey\"=EXCLUDED.\"storageKey\",\"contentType\"=EXCLUDED.\"contentType\","
                + "\"bytes\"=EXCLUDED.\"bytes\",\"revokedAt\"=NULL "
                + "WHERE \"t_resource\".\"ownerId\"=EXCLUDED.\"ownerId\"";
        try {
            return db.inTransaction(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, resourceId);
                    ps.setLong(2, ownerId);
                    ps.setString(3, storageKey == null ? "" : storageKey);
                    ps.setString(4, contentType == null ? "" : contentType);
                    ps.setLong(5, bytes);
                    ps.setLong(6, now);
                    if (ps.executeUpdate() != 1) return false;
                }
                try (PreparedStatement clear = c.prepareStatement(
                        "DELETE FROM \"t_resource_cleanup_queue\" WHERE \"resourceId\"=?")) {
                    clear.setString(1, resourceId);
                    clear.executeUpdate();
                }
                return true;
            });
        } catch (SQLException e) {
            log.error("[resource] 记归属失败 resourceId={}, ownerId={}", resourceId, ownerId, e);
            return false;
        }
    }

    /**
     * 这份素材是不是这个账号传的。
     *
     * <p>🔴 查不到一律返回 false。历史素材（本表建立之前上传的）因此会被判为「不属于你」，
     * 这是<b>刻意的</b>——宁可让老素材发不出去，也不能让「查不到 = 放行」成为默认，
     * 那条默认一旦写下，攻击面就是整个存储桶。</p>
     */
    public boolean ownedBy(String resourceId, long accountId) {
        if (resourceId == null || resourceId.isEmpty() || accountId <= 0) {
            return false;
        }
        if (!persistent()) {
            Long owner = memoryOwner.get(resourceId);
            return owner != null && owner == accountId;
        }
        String sql = "SELECT 1 FROM \"t_resource\" WHERE \"resourceId\"=? AND \"ownerId\"=? "
                + "AND \"revokedAt\" IS NULL";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, resourceId);
            ps.setLong(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            // 🔴 查询异常也判不通过。可用性让位于「不把别人的素材发出去」。
            log.error("[resource] 查归属失败 resourceId={}, accountId={}", resourceId, accountId, e);
            return false;
        }
    }

    /** Fail closed after a multipart object was stored but could not be attached to its business owner. */
    public boolean revoke(String resourceId, long accountId, long now) {
        if (resourceId == null || resourceId.isEmpty() || accountId <= 0) return false;
        if (!persistent()) {
            boolean removed = memoryOwner.remove(resourceId, accountId);
            if (removed) memoryCleanupQueue.add(resourceId);
            return removed;
        }
        try {
            return db.inTransaction(c -> {
                try (PreparedStatement revoke = c.prepareStatement(
                        "UPDATE \"t_resource\" SET \"revokedAt\"=? "
                                + "WHERE \"resourceId\"=? AND \"ownerId\"=?")) {
                    revoke.setLong(1, now);
                    revoke.setString(2, resourceId);
                    revoke.setLong(3, accountId);
                    if (revoke.executeUpdate() != 1) return false;
                }
                try (PreparedStatement queue = c.prepareStatement(
                        "INSERT INTO \"t_resource_cleanup_queue\"(\"resourceId\",\"reason\",\"status\",\"queuedAt\") "
                                + "VALUES(?,'onboarding_attach_failed','pending',?) ON CONFLICT (\"resourceId\") "
                                + "DO UPDATE SET \"reason\"=EXCLUDED.\"reason\",\"status\"='pending',"
                                + "\"queuedAt\"=EXCLUDED.\"queuedAt\"")) {
                    queue.setString(1, resourceId);
                    queue.setLong(2, now);
                    queue.executeUpdate();
                }
                return true;
            });
        } catch (SQLException e) {
            log.error("[resource] 撤销孤立素材失败 resourceId={}, ownerId={}", resourceId, accountId, e);
            return false;
        }
    }

    boolean cleanupQueued(String resourceId) {
        if (!persistent()) return memoryCleanupQueue.contains(resourceId);
        String sql = "SELECT 1 FROM \"t_resource_cleanup_queue\" WHERE \"resourceId\"=? AND \"status\"='pending'";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, resourceId);
            try (ResultSet rows = ps.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }
}
