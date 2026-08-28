package com.echo.gateway;

import com.aengine.network.support.PacketHandlerManager;
import com.aengine.network.support.PlayerSessionManager;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;

/**
 * Echo 网关会话管理器：复用 Aengine {@link PlayerSessionManager}。
 *
 * <p>泛型 {@code K=Long} 表示玩家身份用账号 ID（雪花 ID）。它作为 {@code IEventListener}
 * 注册到 {@link com.aengine.network.netty.websocket.WebSocketServer}：
 * 连接建立时创建 {@link com.aengine.network.support.PlayerSession}，收到包后投递到线程池，
 * 由 {@link PacketHandlerManager#forward} 分发给业务 Handler。</p>
 *
 * <p>BE-1 仅打通会话接入；登录鉴权、在线态、踢人等在后续里程碑补充。</p>
 */
@Slf4j
public class EchoSessionManager extends PlayerSessionManager<Long> {

    public EchoSessionManager(ExecutorService pool, PacketHandlerManager handlerManager) {
        super(pool, handlerManager);
    }

    @Override
    public void offline(Long identity) {
        log.info("玩家离线, accountId={}", identity);
    }
}
