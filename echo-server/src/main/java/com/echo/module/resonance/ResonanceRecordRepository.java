package com.echo.module.resonance;

import com.aengine.persistence.annotation.CRepository;
import com.echo.infra.persistence.CachedPgRepository;

/**
 * {@link ResonanceRecord} 仓储（PostgreSQL + 内存缓存）。
 *
 * @deprecated 随 {@link ResonanceRecord} 一并废弃，已无装配点与调用方。
 */
@Deprecated(since = "0.1.0", forRemoval = true)
@CRepository(source = "echo")
public class ResonanceRecordRepository extends CachedPgRepository<ResonanceRecord> {
}
