package com.echo.gateway;

import com.aengine.network.support.HandlerMethod;
import com.aengine.network.support.IPacketHandler;
import com.aengine.network.support.IoSession;
import com.echo.proto.system.Heartbeat_9001;
import com.echo.proto.system.HeartbeatAck_9002;
import lombok.extern.slf4j.Slf4j;

/**
 * 心跳协议处理器（TECH-P1 §3.1，号段 90xx）。
 *
 * <p>{@code 9001} 放入 {@code noNeedCheckMessage} 白名单：心跳是连接级保活、非业务操作，
 * 登录前后均可发送、不依赖会话身份；这样握手后未登录期也能保活，逻辑统一且最轻量。
 * 收到任意上行包时引擎已在 {@code EventDispatcher.onReceive} 刷新读时间（重置 idle），
 * 本 handler 仅负责回 {@link HeartbeatAck_9002}（便于客户端测 RTT / 确认链路）。</p>
 */
@Slf4j
@IPacketHandler(noNeedCheckMessage = {9001})
public class HeartbeatHandler {

    @HandlerMethod
    public void onHeartbeat(IoSession session, Heartbeat_9001 req) {
        session.send(HeartbeatAck_9002.newBuilder()
                .setServerTime(System.currentTimeMillis())
                .build());
    }
}
