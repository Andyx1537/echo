package com.echo.module.account;

import com.aengine.util.id.IDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录核心逻辑单测：openId 不存在则建号、存在则复用。
 *
 * <p>仓储以 mock 替代（不连真实 MySQL），符合 Aengine "外部服务一律 mock" 的约定。
 * Mockito 通过 Objenesis 绕过 {@link AccountRepository} 的连库构造函数，无需 DB。</p>
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, new IDGenerator(1));
    }

    @Test
    void loginCreatesAccountWhenOpenIdMissing() {
        when(accountRepository.get("openId", "open-new")).thenReturn(null);

        LoginOutcome outcome = accountService.login("open-new");

        assertThat(outcome.newAccount()).isTrue();
        assertThat(outcome.account().getOpenId()).isEqualTo("open-new");
        assertThat(outcome.account().getId()).isPositive();
        assertThat(outcome.account().getCreateTime()).isPositive();
        verify(accountRepository).add(any(Account.class));
    }

    @Test
    void loginReusesAccountWhenOpenIdExists() {
        Account existing = new Account();
        existing.setId(123456789L);
        existing.setOpenId("open-old");
        when(accountRepository.get("openId", "open-old")).thenReturn(existing);

        LoginOutcome outcome = accountService.login("open-old");

        assertThat(outcome.newAccount()).isFalse();
        assertThat(outcome.account()).isSameAs(existing);
        assertThat(outcome.account().getId()).isEqualTo(123456789L);
        verify(accountRepository, never()).add(any(Account.class));
    }

    @Test
    void loginAssignsDistinctSnowflakeIds() {
        when(accountRepository.get(eq("openId"), any())).thenReturn(null);

        long id1 = accountService.login("a").account().getId();
        long id2 = accountService.login("b").account().getId();

        assertThat(id1).isNotEqualTo(id2);
    }
}
