package com.echo.gateway;

import com.aengine.network.support.PlayerSession;
import com.aengine.util.id.IDGenerator;
import com.echo.module.account.Account;
import com.echo.module.account.AccountRepository;
import com.echo.module.account.AccountService;
import com.echo.proto.account.LoginReq_1001;
import com.echo.proto.account.LoginResp_1002;
import com.google.protobuf.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录 Handler 单测：验证回包正确 + 会话身份设置（mock 仓储/会话/会话管理器）。
 */
@ExtendWith(MockitoExtension.class)
class LoginHandlerTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private EchoSessionManager sessionManager;

    @Mock
    private PlayerSession<Long> session;

    private LoginHandler loginHandler;

    @BeforeEach
    void setUp() {
        AccountService accountService = new AccountService(accountRepository, new IDGenerator(1));
        loginHandler = new LoginHandler(accountService, sessionManager);
    }

    @Test
    void onLoginCreatesAccountAndRepliesOk() {
        when(accountRepository.get("openId", "open-x")).thenReturn(null);

        loginHandler.onLogin(session, LoginReq_1001.newBuilder().setOpenId("open-x").build());

        LoginResp_1002 resp = captureResponse();
        assertThat(resp.getCode()).isZero();
        assertThat(resp.getNewAccount()).isTrue();
        assertThat(resp.getAccountId()).isPositive();
        // 会话身份按引擎入口设置（注册在线表 + 踢重复登录）
        verify(sessionManager).setIdentity(eq(resp.getAccountId()), eq(session));
    }

    @Test
    void onLoginReusesExistingAccount() {
        Account existing = new Account();
        existing.setId(987654321L);
        existing.setOpenId("open-y");
        when(accountRepository.get("openId", "open-y")).thenReturn(existing);

        loginHandler.onLogin(session, LoginReq_1001.newBuilder().setOpenId("open-y").build());

        LoginResp_1002 resp = captureResponse();
        assertThat(resp.getCode()).isZero();
        assertThat(resp.getNewAccount()).isFalse();
        assertThat(resp.getAccountId()).isEqualTo(987654321L);
        verify(accountRepository, never()).add(any(Account.class));
        verify(sessionManager).setIdentity(eq(987654321L), eq(session));
    }

    @Test
    void onLoginRejectsBlankOpenId() {
        loginHandler.onLogin(session, LoginReq_1001.getDefaultInstance());

        LoginResp_1002 resp = captureResponse();
        assertThat(resp.getCode()).isEqualTo(1);
        verify(sessionManager, never()).setIdentity(any(), any());
        verify(accountRepository, never()).add(any(Account.class));
    }

    private LoginResp_1002 captureResponse() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(session).send(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(LoginResp_1002.class);
        return (LoginResp_1002) captor.getValue();
    }
}
