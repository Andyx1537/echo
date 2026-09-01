package com.echo.module.social;

import com.aengine.persistence.annotation.CRepository;
import com.aengine.persistence.db.CachedJDBCRepository;

/**
 * {@link Stall} 仓储（PostgreSQL + 内存缓存）。
 */
@CRepository(source = "echo")
public class StallRepository extends CachedJDBCRepository<Stall> {
}
