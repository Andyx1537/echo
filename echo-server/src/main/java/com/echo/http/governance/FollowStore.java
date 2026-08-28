package com.echo.http.governance;

import com.echo.infra.persistence.PgDb;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 关注关系存储（{@code t_follow}）。单向：{@code accountId} 关注 {@code targetId}。
 *
 * <p>关注是一条<b>持续的内容投递通道</b>——{@code E1b} 的定义是「以后也想看见这个人发的」。
 * 与亲友（{@code t_relation}）不是一回事：亲友是双方都认的一层关系，关注是我单方面的订阅。</p>
 *
 * <p>无 DB 时退化为内存态，语义一致（与 {@link BlockStore} 同构）。</p>
 *
 * <h2>🔴 有粉丝数，但不许有「谁粉丝最多」</h2>
 *
 * <p>粉丝数<b>公开且精确</b>（{@code E1b}），所以本类给得出 {@link #followerCount}。
 * 但全站<b>不做「最受欢迎作者」榜</b>，所以本类<b>刻意不提供</b>任何「按粉丝数排序 / 取前 N」
 * 的方法。🔴 <b>不要因为"反正数据都在内存里，加个排序很容易"就补上</b>——
 * 榜单不是从零做起才叫做榜单，暴露一个排好序的列表，榜单就已经存在了，
 * 剩下的只是谁来渲染它。</p>
 *
 * <h2>正反双索引</h2>
 *
 * <p>两个方向都是热点：作者主页要「我有多少粉丝」（反向），信息流要「我关注了谁」（正向）。
 * 内存态同时维护两个索引，落库态靠 {@code t_follow} 的主键与 {@code targetId} 索引。</p>
 */
@Slf4j
public final class FollowStore implements BlockService.FollowUnlinker {

    private final PgDb db;
    /** accountId -> 他关注了谁。 */
    private final Map<Long, Set<Long>> following = new ConcurrentHashMap<>();
    /** targetId -> 谁关注了他。🔴 粉丝数读这一个。 */
    private final Map<Long, Set<Long>> followers = new ConcurrentHashMap<>();

    public FollowStore(PgDb db) {
        this.db = db;
        if (db != null) {
            load();
        }
    }

    private void load() {
        String sql = "SELECT \"accountId\",\"targetId\" FROM \"t_follow\"";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                index(rs.getLong(1), rs.getLong(2));
            }
        } catch (SQLException e) {
            // 读不到就不能启动：粉丝数是公开且精确的数字，以"零关注"启动会让每个作者
            // 看到自己粉丝清零。宁可起不来，也不要给出一个错的精确数。
            throw new IllegalStateException("读取 t_follow 失败，拒绝以「无关注」状态启动", e);
        }
    }

    private void index(long accountId, long targetId) {
        following.computeIfAbsent(accountId, k -> ConcurrentHashMap.newKeySet()).add(targetId);
        followers.computeIfAbsent(targetId, k -> ConcurrentHashMap.newKeySet()).add(accountId);
    }

    private void deindex(long accountId, long targetId) {
        Set<Long> f = following.get(accountId);
        if (f != null) {
            f.remove(targetId);
        }
        Set<Long> r = followers.get(targetId);
        if (r != null) {
            r.remove(accountId);
        }
    }

    /** {@code accountId} 是否关注了 {@code targetId}。 */
    public boolean isFollowing(long accountId, long targetId) {
        Set<Long> s = following.get(accountId);
        return s != null && s.contains(targetId);
    }

    /** {@code targetId} 的粉丝数。🔴 公开且精确，不做模糊化（{@code E1b}）。 */
    public int followerCount(long targetId) {
        Set<Long> s = followers.get(targetId);
        return s == null ? 0 : s.size();
    }

    /** {@code accountId} 关注了多少人。 */
    public int followingCount(long accountId) {
        Set<Long> s = following.get(accountId);
        return s == null ? 0 : s.size();
    }

    /**
     * 建立关注。幂等：已存在则不产生第二行、也不刷新时间。
     *
     * @return true 表示本次真的新建了
     */
    public boolean follow(long id, long accountId, long targetId, long now) {
        if (isFollowing(accountId, targetId)) {
            return false;
        }
        if (db != null) {
            String sql = """
                    INSERT INTO "t_follow" ("id","accountId","targetId","createdAt") VALUES (?,?,?,?)
                    ON CONFLICT ("accountId","targetId") DO NOTHING
                    """;
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.setLong(2, accountId);
                ps.setLong(3, targetId);
                ps.setLong(4, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("写 t_follow 失败", e);
            }
        }
        index(accountId, targetId);
        return true;
    }

    /**
     * 取消关注。
     *
     * <p>物理删除：「我曾经关注过谁」对系统没有用处，而它是 PIPL 下的行为个人信息。</p>
     *
     * @return true 表示本次真的删掉了一行
     */
    public boolean unfollow(long accountId, long targetId) {
        if (!isFollowing(accountId, targetId)) {
            return false;
        }
        if (db != null) {
            String sql = "DELETE FROM \"t_follow\" WHERE \"accountId\"=? AND \"targetId\"=?";
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, accountId);
                ps.setLong(2, targetId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("删 t_follow 失败", e);
            }
        }
        deindex(accountId, targetId);
        return true;
    }

    /**
     * 拉黑的连带动作：解除两个方向的关注（{@link BlockService.FollowUnlinker}）。
     *
     * <p>🔴 <b>不可逆</b>：这里是物理删除，解除拉黑不会把粉丝关系还回来。
     * 代价（误拉黑再解除 = 作者永久少一个粉丝，而粉丝数是精确公开的，作者看得出来）
     * 已在 {@link BlockService#block} 的注释里写明并被接受。</p>
     */
    @Override
    public int unlinkBothDirections(long a, long b) {
        int n = 0;
        if (unfollow(a, b)) {
            n++;
        }
        if (unfollow(b, a)) {
            n++;
        }
        return n;
    }
}
