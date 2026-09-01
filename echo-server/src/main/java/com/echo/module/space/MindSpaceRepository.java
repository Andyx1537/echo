package com.echo.module.space;

import com.aengine.persistence.annotation.CRepository;
import com.aengine.persistence.db.CachedJDBCRepository;

/**
 * {@link MindSpace} 仓储（PostgreSQL + 内存缓存）。
 */
@CRepository(source = "echo")
public class MindSpaceRepository extends CachedJDBCRepository<MindSpace> {
}
