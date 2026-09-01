package com.echo.module.social;

import com.aengine.persistence.annotation.CRepository;
import com.aengine.persistence.db.CachedJDBCRepository;

/**
 * {@link Friendship} 仓储（PostgreSQL + 内存缓存）。
 */
@CRepository(source = "echo")
public class FriendshipRepository extends CachedJDBCRepository<Friendship> {
}
