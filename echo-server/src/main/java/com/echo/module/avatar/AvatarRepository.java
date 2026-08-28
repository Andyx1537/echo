package com.echo.module.avatar;

import com.aengine.persistence.annotation.CRepository;
import com.echo.infra.persistence.CachedPgRepository;

/**
 * {@link Avatar} 仓储（PostgreSQL + 内存缓存）。
 */
@CRepository(source = "echo")
public class AvatarRepository extends CachedPgRepository<Avatar> {
}
