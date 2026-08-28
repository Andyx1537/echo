package com.echo.infra.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * PostgreSQL 连接/连接池封装（HikariCP + org.postgresql）。
 *
 * <p>镜像 Aengine {@code com.aengine.persistence.db.DB} 的对外用法（getConnection/query/update/batch），
 * 但面向 PostgreSQL。为支持"不连真实库也能编译/启动"，构造数据源时设置
 * {@code initializationFailTimeout=-1}：HikariCP 不在构造期主动建连，仅在首次
 * {@link #getConnection()} 时惰性连接。配置开关由 {@link com.echo.bootstrap.EchoDatabase} 控制，
 * 缺省不创建本类实例（不连库）。</p>
 */
@Slf4j
public class PgDb {

    private final String name;

    private final DataSource dataSource;

    private int slowLog = 100;

    public PgDb(Properties properties) {
        this.name = properties.getProperty("db.name", "echo");
        this.dataSource = createDataSource(properties);
        if (properties.containsKey("db.slowLog")) {
            this.slowLog = Integer.parseInt(properties.getProperty("db.slowLog").trim());
        }
    }

    /** 测试/嵌入用：直接注入已构建的数据源。 */
    public PgDb(String name, DataSource dataSource, int slowLog) {
        this.name = name;
        this.dataSource = dataSource;
        this.slowLog = slowLog;
    }

    private static HikariDataSource createDataSource(Properties properties) {
        HikariConfig config = new HikariConfig();
        String url = firstNonNull(properties, "jdbcUrl", "url", "db.url");
        if (url != null) {
            config.setJdbcUrl(url);
        }
        String driver = firstNonNull(properties, "driverClassName", "db.driver");
        config.setDriverClassName(driver != null ? driver : "org.postgresql.Driver");
        String username = firstNonNull(properties, "username", "db.username");
        if (username != null) {
            config.setUsername(username);
        }
        String password = firstNonNull(properties, "password", "db.password");
        if (password != null) {
            config.setPassword(password);
        }
        String maxPool = firstNonNull(properties, "maximumPoolSize", "maxTotal", "maxActive");
        if (maxPool != null) {
            config.setMaximumPoolSize(Integer.parseInt(maxPool.trim()));
        }
        String minIdle = firstNonNull(properties, "minimumIdle", "minIdle");
        if (minIdle != null) {
            config.setMinimumIdle(Integer.parseInt(minIdle.trim()));
        }
        String connTimeout = firstNonNull(properties, "connectionTimeout", "maxWaitMillis");
        if (connTimeout != null) {
            config.setConnectionTimeout(Long.parseLong(connTimeout.trim()));
        }
        // 关键：不在构造期主动建连，保证无库环境也能启动（惰性连接）
        config.setInitializationFailTimeout(-1);
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("hikari.")) {
                config.addDataSourceProperty(key.substring("hikari.".length()), properties.getProperty(key));
            }
        }
        return new HikariDataSource(config);
    }

    private static String firstNonNull(Properties properties, String... keys) {
        for (String key : keys) {
            String value = properties.getProperty(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public String getName() {
        return name;
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /** 事务体：拿到一条已关自动提交的连接，正常返回则提交，抛异常则整体回滚。 */
    @FunctionalInterface
    public interface TxWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * 在单条连接上以一个事务执行 {@code work}。
     *
     * <p>需要它的原因：{@link #update(String, PgStatementBinder)} 每次自己取连接、用完归还，
     * 所以每条语句各成一个事务。凡是"几处写必须同生同灭"的场景（审核处置要同时改卡状态、写
     * {@code reviewedAt}、落两条流水）都不能用它拼——分开写会留下「状态变了但没留痕」这类
     * 事后修不回来的不一致：{@code reviewedAt} 有禁改触发器，流水表只追加。</p>
     */
    public <T> T inTransaction(TxWork<T> work) throws SQLException {
        long t = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection()) {
            boolean prevAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    log.error("事务回滚失败", rollbackFailure);
                }
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(prevAutoCommit);
                } catch (SQLException ignored) {
                    // 连接即将归还池中，恢复 autoCommit 失败不影响本次结果
                }
            }
        } finally {
            warnSlow("transaction", t);
        }
    }

    public int update(String sql) throws SQLException {
        return update(sql, null);
    }

    public int update(String sql, PgStatementBinder binder) throws SQLException {
        if (log.isDebugEnabled()) {
            log.debug(sql);
        }
        long t = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            if (binder != null) {
                binder.bind(ps);
            }
            return ps.executeUpdate();
        } finally {
            warnSlow(sql, t);
        }
    }

    public int[] batch(List<String> sqls) throws SQLException {
        long t = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String s : sqls) {
                statement.addBatch(s);
            }
            return statement.executeBatch();
        } finally {
            warnSlow("batch", t);
        }
    }

    public List<Map<String, Object>> query(String sql) throws SQLException {
        return query(sql, null);
    }

    public List<Map<String, Object>> query(String sql, PgStatementBinder binder) throws SQLException {
        if (log.isDebugEnabled()) {
            log.debug(sql);
        }
        long t = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            if (binder != null) {
                binder.bind(ps);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> list = new ArrayList<>();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> map = new HashMap<>();
                    for (int i = 0; i < md.getColumnCount(); i++) {
                        String label = md.getColumnLabel(i + 1);
                        map.put(label, rs.getObject(label));
                    }
                    list.add(map);
                }
                return list;
            }
        } finally {
            warnSlow(sql, t);
        }
    }

    private void warnSlow(String sql, long start) {
        long cost = System.currentTimeMillis() - start;
        if (cost > slowLog && log.isWarnEnabled()) {
            log.warn("slow sql [{}ms] {}", cost, sql);
        }
    }

    public void shutdown() {
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }
}
