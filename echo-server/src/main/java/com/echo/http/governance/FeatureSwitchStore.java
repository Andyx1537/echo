package com.echo.http.governance;

import com.echo.infra.persistence.PgDb;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 功能开关的持久化（{@code t_feature_switch}）。
 *
 * <p>🔴 本表<b>只存开关状态，不存治理能力就绪状态</b>。就绪与否由
 * {@link CapabilityRegistry} 在运行时探测——落库就等于给了一条「改一行数据即可绕过前置」的路。</p>
 *
 * <p>无 DB 时退化为内存态（联调用），语义一致。</p>
 */
@Slf4j
public final class FeatureSwitchStore {

    /** 一个开关的当前状态。 */
    public record State(String key, boolean enabled, long updatedBy, long updatedAt,
                        Long approvedBy, Long approvedAt) {
    }

    private final PgDb db;
    /** 读多写极少，缓存全量；写时同步更新。无 DB 时它就是唯一真相。 */
    private final Map<String, State> cache = new ConcurrentHashMap<>();

    public FeatureSwitchStore(PgDb db) {
        this.db = db;
        if (db != null) {
            load();
        } else {
            // 🔴 无 DB 也必须是「默认关闭」，不能因为没库就放开
            for (String key : FeatureSwitchService.KEYS) {
                cache.put(key, new State(key, false, 0, 0, null, null));
            }
        }
    }

    private void load() {
        String sql = "SELECT \"key\",\"enabled\",\"updatedBy\",\"updatedAt\",\"approvedBy\",\"approvedAt\" "
                + "FROM \"t_feature_switch\"";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String key = rs.getString(1);
                long approvedBy = rs.getLong(5);
                long approvedAt = rs.getLong(6);
                cache.put(key, new State(key, rs.getBoolean(2), rs.getLong(3), rs.getLong(4),
                        rs.wasNull() ? null : approvedBy, approvedAt == 0 ? null : approvedAt));
            }
        } catch (SQLException e) {
            // 读不到就按全关处理：开关的失败方向必须是"关"
            log.error("读取 t_feature_switch 失败，全部开关按关闭处理", e);
        }
    }

    /** 开关是否开启。未知 key 一律 false（默认关闭，不是默认放开）。 */
    public boolean isEnabled(String key) {
        State s = cache.get(key);
        return s != null && s.enabled();
    }

    public State get(String key) {
        return cache.get(key);
    }

    /** 落盘并更新缓存。 */
    public State setEnabled(String key, boolean enabled, long operatorId, Long approvedBy, long now) {
        State next = new State(key, enabled, operatorId, now, approvedBy, approvedBy == null ? null : now);
        if (db != null) {
            String sql = """
                    INSERT INTO "t_feature_switch" ("key","enabled","updatedBy","updatedAt","approvedBy","approvedAt")
                    VALUES (?,?,?,?,?,?)
                    ON CONFLICT ("key") DO UPDATE SET
                        "enabled"=EXCLUDED."enabled",
                        "updatedBy"=EXCLUDED."updatedBy",
                        "updatedAt"=EXCLUDED."updatedAt",
                        "approvedBy"=EXCLUDED."approvedBy",
                        "approvedAt"=EXCLUDED."approvedAt"
                    """;
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key);
                ps.setBoolean(2, enabled);
                ps.setLong(3, operatorId);
                ps.setLong(4, now);
                if (approvedBy == null) {
                    ps.setNull(5, java.sql.Types.BIGINT);
                    ps.setNull(6, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(5, approvedBy);
                    ps.setLong(6, now);
                }
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("写 t_feature_switch 失败: " + key, e);
            }
        }
        cache.put(key, next);
        return next;
    }
}
