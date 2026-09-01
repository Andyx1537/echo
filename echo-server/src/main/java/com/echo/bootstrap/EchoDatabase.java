package com.echo.bootstrap;

import com.echo.infra.persistence.PgDb;
import com.echo.infra.persistence.PgDbManager;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 可选的 PostgreSQL 数据源初始化（默认关闭）。
 *
 * <p>架构 B'：单库 PostgreSQL（含 pgvector）。通过系统属性开关
 * {@code -Decho.db.enabled=true} 开启；缺省关闭时整个进程不接触 PG，保证无库也能
 * 编译并启动到 WebSocket 就绪。开启后从 {@code -Decho.db.config=<path>} 指定的
 * properties 文件加载配置并注册到 {@link PgDbManager}（数据源名须与各仓储
 * {@code @CRepository(source=...)} 一致，本工程为 {@code echo}）。</p>
 *
 * <p>配置文件键：</p>
 * <pre>
 * db.name=echo
 * db.dialect=postgresql
 * jdbcUrl=jdbc:postgresql://127.0.0.1:5432/echo
 * driverClassName=org.postgresql.Driver
 * username=echo
 * password=echo
 * maximumPoolSize=8
 * </pre>
 */
@Slf4j
public final class EchoDatabase {

    /** 与 schema.sql 当前基线一致；后续每个迁移版本只递增不复用。 */
    public static final long REQUIRED_SCHEMA_VERSION = 2026083102L;

    /** 开启 DB 的系统属性开关。 */
    public static final String PROP_DB_ENABLED = "echo.db.enabled";

    /** 数据源配置文件路径的系统属性键。 */
    public static final String PROP_DB_CONFIG = "echo.db.config";

    private EchoDatabase() {
    }

    /** DB 是否开启（默认关闭）。 */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(PROP_DB_ENABLED, "false"));
    }

    /**
     * 按开关初始化 PG 数据源；关闭或失败时返回 false（不抛出，保证进程仍可启动到 WebSocket）。
     *
     * @return 是否成功接入 DB
     */
    public static boolean initIfEnabled() {
        if (!isEnabled()) {
            log.warn("DB 未开启（{}=false），跳过 PostgreSQL 初始化；仓储类不实例化。", PROP_DB_ENABLED);
            return false;
        }
        String configPath = System.getProperty(PROP_DB_CONFIG);
        if (configPath == null || configPath.isBlank()) {
            throw new IllegalStateException("已开启 DB 但未提供 " + PROP_DB_CONFIG);
        }
        Path path = Path.of(configPath);
        if (!Files.exists(path)) {
            throw new IllegalStateException("DB 配置文件不存在: " + configPath);
        }
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(path.toFile())) {
            properties.load(in);
            PgDb db = new PgDb(properties);
            verifySchemaVersion(db);
            PgDbManager.getInstance().add(db);
            log.info("PostgreSQL 数据源已注册: name={}", properties.getProperty("db.name", "echo"));
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("初始化 PostgreSQL 或校验 Schema 失败", e);
        }
    }

    private static void verifySchemaVersion(PgDb db) throws Exception {
        var rows = db.query("SELECT MAX(\"version\") AS \"version\" FROM \"t_schema_version\"");
        Object value = rows.isEmpty() ? null : rows.get(0).get("version");
        long actual = value instanceof Number number ? number.longValue() : -1L;
        if (actual != REQUIRED_SCHEMA_VERSION) {
            db.shutdown();
            throw new IllegalStateException("Schema 版本不匹配: required=" + REQUIRED_SCHEMA_VERSION
                    + ", actual=" + actual + "；请先执行 echo-server/src/main/resources/sql/schema.sql");
        }
    }
}
