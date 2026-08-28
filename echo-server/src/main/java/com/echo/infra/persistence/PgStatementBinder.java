package com.echo.infra.persistence;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 为参数化 SQL 绑定占位符的回调（方言无关）。
 *
 * <p>等价于 Aengine 的 {@code PreparedStatementBinder}，这里在 echo 持久化层内自带一份，
 * 以避免依赖 Aengine 的 MySQL 持久化包。</p>
 */
@FunctionalInterface
public interface PgStatementBinder {
    void bind(PreparedStatement ps) throws SQLException;
}
