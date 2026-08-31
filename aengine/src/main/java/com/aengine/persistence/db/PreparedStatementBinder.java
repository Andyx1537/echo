package com.aengine.persistence.db;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 为参数化 SQL 绑定占位符的回调。由调用方按列类型安全地填充 {@link PreparedStatement} 的 {@code ?} 参数，
 * 从而避免将值直接拼接进 SQL 造成的注入风险。
 */
@FunctionalInterface
public interface PreparedStatementBinder {
    void bind(PreparedStatement ps) throws SQLException;
}
