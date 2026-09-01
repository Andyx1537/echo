package com.echo.infra.persistence;

import lombok.extern.slf4j.Slf4j;

import com.aengine.persistence.db.DB;
import com.aengine.persistence.db.DBManager;

/**
 * PostgreSQL 数据源注册表（镜像 Aengine {@code DBManager} 中按名取库的用法）。
 *
 * <p>兼容旧 HTTP Store 的门面，底层唯一注册表已经是 Aengine {@link DBManager}。
 * 新 Repository 直接按 {@code @CRepository.source()} 从 Aengine 取库。</p>
 */
@Slf4j
public final class PgDbManager {

    private static final PgDbManager INSTANCE = new PgDbManager();

    private PgDbManager() {
    }

    public static PgDbManager getInstance() {
        return INSTANCE;
    }

    public void add(PgDb db) {
        DB old = DBManager.getInstance().get(db.getName());
        if (old == null) {
            DBManager.getInstance().add(db);
        } else if (old != db) {
            log.warn("PG 数据源已存在: {}", db.getName());
        }
    }

    public PgDb get(String name) {
        DB db = DBManager.getInstance().get(name);
        if (db == null) {
            return null;
        }
        if (!(db instanceof PgDb pgDb)) {
            throw new IllegalStateException("数据源不是 Echo PostgreSQL 兼容类型: " + name);
        }
        return pgDb;
    }

    public void remove(String name) {
        DBManager.getInstance().remove(name);
    }

    public void shutdown() {
        DBManager.getInstance().shutdown();
    }
}
