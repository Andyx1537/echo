package com.echo.module.mind;

import com.aengine.persistence.annotation.CRepository;
import com.echo.infra.persistence.CachedPgRepository;

/**
 * {@link SelfVector} 仓储（PostgreSQL + 内存缓存）。
 *
 * <p>注意：向量本体列（pgvector {@code vector}）不归本仓储的通用 CRUD 管，
 * 由 {@code IVectorStore} 通道处理；本仓储只管理向量元数据列。</p>
 */
@CRepository(source = "echo")
public class SelfVectorRepository extends CachedPgRepository<SelfVector> {
}
