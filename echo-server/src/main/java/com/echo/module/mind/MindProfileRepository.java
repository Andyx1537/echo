package com.echo.module.mind;

import com.aengine.persistence.annotation.CRepository;
import com.echo.infra.persistence.CachedPgRepository;

/**
 * {@link MindProfile} 仓储（PostgreSQL + 内存缓存）。
 */
@CRepository(source = "echo")
public class MindProfileRepository extends CachedPgRepository<MindProfile> {
}
