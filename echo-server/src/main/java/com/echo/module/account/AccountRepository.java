package com.echo.module.account;

import com.aengine.persistence.annotation.CRepository;
import com.aengine.persistence.db.CachedJDBCRepository;

/**
 * {@link Account} 仓储：Aengine Repository + PostgreSQL 方言 + 内存二级缓存。
 *
 * <p>{@code source="echo"} 指向 Aengine DBManager 中名为 {@code echo} 的数据源；
 * 仅在启动类开启 DB 时实例化（构造即取库并 fixTable 建表），单测中一律以 mock 替代。</p>
 */
@CRepository(source = "echo")
public class AccountRepository extends CachedJDBCRepository<Account> {
}
