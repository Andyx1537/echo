package com.echo.module.account;

import com.aengine.util.id.IDGenerator;
import lombok.extern.slf4j.Slf4j;

/**
 * 账号领域服务：登录纵切的核心业务（不感知协议/会话，便于单测）。
 *
 * <p>依赖以构造注入，便于在单测中用 mock 仓储替换真实 DB：</p>
 * <ul>
 *   <li>{@link AccountRepository} —— 落 PostgreSQL + 内存缓存的账号仓储</li>
 *   <li>{@link IDGenerator} —— Aengine 雪花 ID 生成器，建号时分配主键</li>
 * </ul>
 */
@Slf4j
public class AccountService {

    private final AccountRepository accountRepository;

    private final IDGenerator idGenerator;

    public AccountService(AccountRepository accountRepository, IDGenerator idGenerator) {
        this.accountRepository = accountRepository;
        this.idGenerator = idGenerator;
    }

    /**
     * 按 openId 登录：存在则复用，不存在则建号。
     *
     * @param openId 外部登录唯一标识
     * @return 登录结果（账号 + 是否新建）
     */
    public LoginOutcome login(String openId) {
        // openId 上有唯一索引/缓存，按列查询命中缓存或回源 DB
        Account existing = accountRepository.get("openId", openId);
        if (existing != null) {
            log.debug("openId={} 命中已有账号 id={}", openId, existing.getId());
            return new LoginOutcome(existing, false);
        }
        Account account = new Account();
        account.setId(idGenerator.nextId());
        account.setOpenId(openId);
        account.setStatus(0);
        account.setCreateTime(System.currentTimeMillis());
        accountRepository.add(account);
        log.info("openId={} 新建账号 id={}", openId, account.getId());
        return new LoginOutcome(account, true);
    }
}
