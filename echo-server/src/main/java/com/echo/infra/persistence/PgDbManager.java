package com.echo.infra.persistence;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PostgreSQL 数据源注册表（镜像 Aengine {@code DBManager} 中按名取库的用法）。
 *
 * <p>{@link PgRepository} 构造时通过 {@code @CRepository.source()} 从这里取 {@link PgDb}。
 * 缺省为空，未注册任何数据源时取库返回 null —— 这就是"不连库"的默认态：
 * 只要不实例化仓储，进程即可在无库环境编译/启动。</p>
 */
@Slf4j
public final class PgDbManager {

    private static final PgDbManager INSTANCE = new PgDbManager();

    private final Map<String, PgDb> databases = new ConcurrentHashMap<>();

    private PgDbManager() {
    }

    public static PgDbManager getInstance() {
        return INSTANCE;
    }

    public void add(PgDb db) {
        PgDb old = databases.putIfAbsent(db.getName(), db);
        if (old != null && old != db) {
            log.warn("PG 数据源已存在: {}", db.getName());
        }
    }

    public PgDb get(String name) {
        return databases.get(name);
    }

    public void remove(String name) {
        databases.remove(name);
    }

    public void shutdown() {
        databases.values().forEach(PgDb::shutdown);
        databases.clear();
    }
}
