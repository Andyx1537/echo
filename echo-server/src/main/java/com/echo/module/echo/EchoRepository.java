package com.echo.module.echo;

import com.aengine.persistence.annotation.CRepository;
import com.aengine.persistence.db.CachedJDBCRepository;

import java.sql.SQLException;

/**
 * {@link Echo} 仓储（PostgreSQL + 内存缓存）。
 */
@CRepository(source = "echo")
public class EchoRepository extends CachedJDBCRepository<Echo> {

    /**
     * 删除已过期回声（{@code expireAt < now}），供 scheduler 定时清理。
     *
     * <p>走范围删除 SQL（通用 CRUD 只支持等值索引查询，不覆盖范围条件），
     * 直接落库；过期行的二级缓存最终随 TTL 失效，且读取侧（pullEchoes）已按
     * {@code expireAt > now} 过滤，缓存短暂滞后无副作用。</p>
     *
     * @param now 当前时间(ms)
     * @return 删除行数
     */
    public int removeExpired(long now) {
        try {
            return db.update("DELETE FROM \"" + meta.getName() + "\" WHERE \"expireAt\" < ?",
                    ps -> ps.setLong(1, now));
        } catch (SQLException e) {
            throw new RuntimeException("purge expired echoes failed", e);
        }
    }
}
