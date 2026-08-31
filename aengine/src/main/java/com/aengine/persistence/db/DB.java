package com.aengine.persistence.db;

import com.aengine.util.thread.NamedThreadFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 数据库辅助类
 *
 */
public class DB {

    private static final Logger log = LoggerFactory.getLogger(DB.class);

    /*
	 * 数据库连接池
     */
    private final DataSource dataSource;

    /*
	 * 缓慢查询
     */
    private int slowLog = 100;

    /*
	 * 名字
     */
    private final String name;

    /*
	 * 持久化的线程池
     */
    private ScheduledThreadPoolExecutor[] pools;

    /**
     * 使用配置文件构造
     *
     * @param properties 配置文件
     */
    public DB(Properties properties) throws Exception {
        this.name = properties.getProperty("db.name");
        this.dataSource = createDataSource(properties);
        if (properties.containsKey("db.slowLog")) {
            this.slowLog = Integer.parseInt(properties.getProperty("db.slowLog"));
        }
        NamedThreadFactory namedThreadFactory = new NamedThreadFactory("db-saver");
        if (properties.containsKey("db.saveThreads")) {
            int threads = Integer.parseInt(properties.getProperty("db.saveThreads"));
            pools = new ScheduledThreadPoolExecutor[threads];
            for (int i = 0; i < threads; i++) {
                pools[i] = new ScheduledThreadPoolExecutor(1, namedThreadFactory);
            }
        }
    }

    public DB(String name, DataSource dataSource, int threads) {
        this(name,dataSource,threads,100);
    }
    
    public DB(String name, DataSource dataSource, int threads, int slowLog) {
    	this.name = name;
        this.dataSource = dataSource;
        this.slowLog = slowLog;
        NamedThreadFactory namedThreadFactory = new NamedThreadFactory("db-saver");
        if (threads > 0) {
            pools = new ScheduledThreadPoolExecutor[threads];
            for (int i = 0; i < threads; i++) {
                pools[i] = new ScheduledThreadPoolExecutor(1, namedThreadFactory);
            }
        }
    }

    /**
     * 基于 HikariCP 创建数据源。兼容历史 dbcp2 风格的属性键
     * （url/username/password/driverClassName/maxTotal/maxIdle 等），
     * 同时透传所有 hikari. 前缀的高级配置项。
     */
    private static HikariDataSource createDataSource(Properties properties) {
        HikariConfig config = new HikariConfig();
        String url = firstNonNull(properties, "jdbcUrl", "url", "db.url");
        if (url != null) {
            config.setJdbcUrl(url);
        }
        String driver = firstNonNull(properties, "driverClassName", "db.driver");
        if (driver != null) {
            config.setDriverClassName(driver);
        }
        String username = firstNonNull(properties, "username", "db.username");
        if (username != null) {
            config.setUsername(username);
        }
        String password = firstNonNull(properties, "password", "db.password");
        if (password != null) {
            config.setPassword(password);
        }
        String maxTotal = firstNonNull(properties, "maximumPoolSize", "maxTotal", "maxActive");
        if (maxTotal != null) {
            config.setMaximumPoolSize(Integer.parseInt(maxTotal.trim()));
        }
        String minIdle = firstNonNull(properties, "minimumIdle", "minIdle");
        if (minIdle != null) {
            config.setMinimumIdle(Integer.parseInt(minIdle.trim()));
        }
        String connTimeout = firstNonNull(properties, "connectionTimeout", "maxWaitMillis", "maxWait");
        if (connTimeout != null) {
            config.setConnectionTimeout(Long.parseLong(connTimeout.trim()));
        }
        // 透传 hikari.xxx 高级配置
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

    public int getSlowLog() {
        return slowLog;
    }
    private int pool_index = 0;

    protected ScheduledExecutorService getPool() {
        if (pools == null) {
            return null;
        }
        if (++pool_index >= pools.length) {
            pool_index = 0;
        }
        return pools[pool_index];
    }

    /**
     * 从连接池中获取一条连接
     *
     * @return 连接
     * @throws SQLException 参考{@link DataSource#getConnection()}
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * 执行更新操作，操作结果参考{@link Statement#executeUpdate(String)}
     *
     * @param sql 更新语句
     * @return 更新语句影响的记录条数
     * @throws SQLException 数据库异常
     */
    public int update(String sql) throws SQLException {
        if (log.isDebugEnabled()) {
            log.debug(sql);
        }
        Connection connection = null;
        Statement statement = null;
        long t = System.currentTimeMillis();
        try {
            connection = dataSource.getConnection();
            statement = connection.createStatement();
            return statement.executeUpdate(sql);
        } finally {
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    log.error("close statement failed", e);
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    log.error("close connection failed", e);
                }
            }
            t = System.currentTimeMillis() - t;
            if (t > slowLog) {
                if (log.isWarnEnabled()) {
                    log.warn("slow sql [" + t + "]" + sql);
                }
            }
        }
    }

    /**
     * 参数化更新：使用 PreparedStatement + 占位符绑定，避免 SQL 注入。
     *
     * @param sql    含 {@code ?} 占位符的更新语句
     * @param binder 占位符绑定回调
     * @return 影响行数
     */
    public int update(String sql, PreparedStatementBinder binder) throws SQLException {
        if (log.isDebugEnabled()) {
            log.debug(sql);
        }
        Connection connection = null;
        PreparedStatement ps = null;
        long t = System.currentTimeMillis();
        try {
            connection = dataSource.getConnection();
            ps = connection.prepareStatement(sql);
            if (binder != null) {
                binder.bind(ps);
            }
            return ps.executeUpdate();
        } finally {
            if (ps != null) {
                try {
                    ps.close();
                } catch (SQLException e) {
                    log.error("close statement failed", e);
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    log.error("close connection failed", e);
                }
            }
            t = System.currentTimeMillis() - t;
            if (t > slowLog) {
                if (log.isWarnEnabled()) {
                    log.warn("slow sql [" + t + "]" + sql);
                }
            }
        }
    }

	/**
	 * 批量执行sql
	 *
	 * @param sql
	 * @return
	 * @throws Exception
	 */
	public int[] batch(List<String> sql) throws Exception {
	    Connection connection = null;
	    Statement statement = null;
	    long t = System.currentTimeMillis();
	    try {
		    connection = dataSource.getConnection();
		    statement = connection.createStatement();
		    for (String s : sql)
		        statement.addBatch(s);
		    return statement.executeBatch();
	    } finally {
		    if (statement != null) {
			    try {
				    statement.close();
			    } catch (SQLException e) {
				    log.error("close statement failed", e);
			    }
		    }
		    if (connection != null) {
			    try {
				    connection.close();
			    } catch (SQLException e) {
				    log.error("close connection failed", e);
			    }
		    }
		    t = System.currentTimeMillis() - t;
		    if (t > slowLog) {
			    if (log.isWarnEnabled()) {
				    log.warn("slow sql [" + t + "]" + sql);
			    }
		    }
	    }
    }

    /**
     * 执行查询操作
     *
     * @param sql 查询语句
     * @return 查询的结果
     * @throws SQLException 执行数据库操作时的异常
     */
    public List<Map<String, Object>> query(String sql) throws SQLException {
        if (log.isDebugEnabled()) {
            log.debug(sql);
        }
        Connection connection = null;
        Statement statement = null;
        ResultSet rs = null;
        long t = System.currentTimeMillis();
        try {
            connection = dataSource.getConnection();
            statement = connection.createStatement();
            rs = statement.executeQuery(sql);
            List<Map<String, Object>> list = new ArrayList<>();
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> map = new HashMap<>();
                for (int i = 0; i < meta.getColumnCount(); i++) {
                    String name = meta.getColumnLabel(i + 1);
                    Object value = rs.getObject(name);
                    map.put(name, value);
                }
                list.add(map);
            }
            return list;
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {
                    log.error("close result failed", e);
                }
            }
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    log.error("close statement failed", e);
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    log.error("close connection failed", e);
                }
            }
            t = System.currentTimeMillis() - t;
            if (t > slowLog) {
                if (log.isWarnEnabled()) {
                    log.warn("slow sql [" + t + "]" + sql);
                }
            }
        }
    }

    /**
     * 参数化查询：使用 PreparedStatement + 占位符绑定，避免 SQL 注入。
     *
     * @param sql    含 {@code ?} 占位符的查询语句
     * @param binder 占位符绑定回调
     * @return 查询结果
     */
    public List<Map<String, Object>> query(String sql, PreparedStatementBinder binder) throws SQLException {
        if (log.isDebugEnabled()) {
            log.debug(sql);
        }
        Connection connection = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        long t = System.currentTimeMillis();
        try {
            connection = dataSource.getConnection();
            ps = connection.prepareStatement(sql);
            if (binder != null) {
                binder.bind(ps);
            }
            rs = ps.executeQuery();
            List<Map<String, Object>> list = new ArrayList<>();
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> map = new HashMap<>();
                for (int i = 0; i < meta.getColumnCount(); i++) {
                    String name = meta.getColumnLabel(i + 1);
                    Object value = rs.getObject(name);
                    map.put(name, value);
                }
                list.add(map);
            }
            return list;
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {
                    log.error("close result failed", e);
                }
            }
            if (ps != null) {
                try {
                    ps.close();
                } catch (SQLException e) {
                    log.error("close statement failed", e);
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    log.error("close connection failed", e);
                }
            }
            t = System.currentTimeMillis() - t;
            if (t > slowLog) {
                if (log.isWarnEnabled()) {
                    log.warn("slow sql [" + t + "]" + sql);
                }
            }
        }
    }

    public void shutdown() {
        if (pools == null) {
            return;
        }
        for (ScheduledThreadPoolExecutor pool : pools) {
            try {
                pool.shutdown();
                while (!pool.isTerminated()) {
                    if (log.isWarnEnabled()) {
                        log.warn(pool.getQueue().size() + " task(s) in save queue.");
                    }
                    try {
                        pool.awaitTermination(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                    }
                }
            } catch (Throwable t) {
                log.error("", t);
            }
        }
    }
}
