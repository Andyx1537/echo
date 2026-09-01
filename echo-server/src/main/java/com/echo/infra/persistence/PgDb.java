package com.echo.infra.persistence;

import com.aengine.persistence.db.DB;
import com.aengine.persistence.dialect.PostgresDialect;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * Echo 对 Aengine {@link DB} 的 PostgreSQL 兼容别名。
 *
 * <p>尚未迁移的 HTTP Store 与 pgvector 通道继续使用本类型，但连接池、参数化 SQL、
 * 批量执行和事务均由 Aengine DB 提供，不再维护第二套实现。</p>
 */
public class PgDb extends DB {
    public PgDb(Properties properties) {
        super(withPostgresDialect(properties));
    }

    public PgDb(String name, DataSource dataSource, int slowLog) {
        super(name, dataSource, 0, slowLog, new PostgresDialect());
    }

    private static Properties withPostgresDialect(Properties source) {
        Properties copy = new Properties();
        copy.putAll(source);
        copy.setProperty("db.dialect", "postgresql");
        return copy;
    }
}
