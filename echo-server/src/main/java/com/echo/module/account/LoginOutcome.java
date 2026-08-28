package com.echo.module.account;

/**
 * 登录结果：命中的/新建的账号，以及是否为本次新建。
 *
 * @param account    账号实体
 * @param newAccount 是否本次新建（true=建号，false=复用）
 */
public record LoginOutcome(Account account, boolean newAccount) {
}
