package com.echo.gateway;

import com.aengine.network.support.HandlerMethod;
import com.aengine.network.support.IPacketHandler;
import com.aengine.network.support.IoSession;
import com.aengine.network.support.PlayerSession;
import com.echo.module.account.Account;
import com.echo.module.account.AccountService;
import com.echo.module.account.LoginOutcome;
import com.echo.proto.account.LoginReq_1001;
import com.echo.proto.account.LoginResp_1002;
import lombok.extern.slf4j.Slf4j;

/**
 * 登录协议处理器（TECH-P1 §3，账号号段 10xx）。
 *
 * <p>登录前无身份，故把登录消息号 {@code 1001} 放进
 * {@link IPacketHandler#noNeedCheckMessage()} 白名单，绕过
 * {@code PacketHandlerManager} 的会话校验。处理方法签名遵循引擎约定
 * {@code (IoSession session, <Proto> req)}，消息号由参数类名 {@code _1001} 反解。</p>
 *
 * <p>会话身份设置：引擎实际入口是
 * {@link com.aengine.network.support.PlayerSessionManager#setIdentity(Object, PlayerSession)}，
 * 它会把 {@link PlayerSession} 注册进在线表并踢掉重复登录的旧连接，比直接
 * {@link PlayerSession#setIdentity(Object)} 更完整，故这里走会话管理器。</p>
 */
@Slf4j
@IPacketHandler(noNeedCheckMessage = {1001})
public class LoginHandler {

    private final AccountService accountService;

    private final EchoSessionManager sessionManager;

    public LoginHandler(AccountService accountService, EchoSessionManager sessionManager) {
        this.accountService = accountService;
        this.sessionManager = sessionManager;
    }

    /**
     * 处理登录请求：按 openId 查/建账号，设置会话身份，回 {@link LoginResp_1002}。
     */
    @HandlerMethod
    @SuppressWarnings("unchecked")
    public void onLogin(IoSession session, LoginReq_1001 req) {
        String openId = req.getOpenId();
        if (openId == null || openId.isBlank()) {
            log.warn("登录失败：openId 为空, remote={}", remoteOf(session));
            session.send(LoginResp_1002.newBuilder()
                    .setCode(1)
                    .setMessage("openId 不能为空")
                    .build());
            return;
        }

        LoginOutcome outcome = accountService.login(openId);
        Account account = outcome.account();

        // 设置会话身份：身份类型 K=Long（账号雪花ID）
        PlayerSession<Long> playerSession = (PlayerSession<Long>) session;
        sessionManager.setIdentity(account.getId(), playerSession);

        session.send(LoginResp_1002.newBuilder()
                .setCode(0)
                .setAccountId(account.getId())
                .setNewAccount(outcome.newAccount())
                .setMessage("ok")
                .build());
        log.info("登录成功 accountId={}, newAccount={}", account.getId(), outcome.newAccount());
    }

    private static String remoteOf(IoSession session) {
        if (session instanceof PlayerSession<?> ps) {
            return ps.getRemoteAddress();
        }
        return "unknown";
    }
}
