package com.echo.module.account;

import com.aengine.persistence.annotation.CRepository;
import com.echo.infra.persistence.CachedPgRepository;

/**
 * {@link Account} 仓储：PostgreSQL + 内存二级缓存（{@link CachedPgRepository}）。
 *
 * <p>{@code source="echo"} 指向 {@code PgDbManager} 中名为 {@code echo} 的数据源；
 * 仅在启动类开启 DB 时实例化（构造即取库并 fixTable 建表），单测中一律以 mock 替代。</p>
 */
@CRepository(source = "echo")
public class AccountRepository extends CachedPgRepository<Account> {
}
