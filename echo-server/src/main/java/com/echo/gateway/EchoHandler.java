package com.echo.gateway;

import com.aengine.network.support.HandlerMethod;
import com.aengine.network.support.IPacketHandler;
import com.aengine.network.support.IoSession;
import com.aengine.network.support.PlayerSession;
import com.echo.module.echo.Echo;
import com.echo.module.echo.EchoService;
import com.echo.proto.echo.EchoListResp_1502;
import com.echo.proto.echo.EchoSnapshot;
import com.echo.proto.echo.LeaveTraceReq_1503;
import com.echo.proto.echo.LeaveTraceResp_1504;
import com.echo.proto.echo.PullEchoesReq_1501;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 回声协议处理器（TECH-P1 §4.3，号段 15xx）。已登录校验由引擎完成。
 */
@Slf4j
@IPacketHandler
public class EchoHandler {

    private final EchoService echoService;

    public EchoHandler(EchoService echoService) {
        this.echoService = echoService;
    }

    @HandlerMethod
    @SuppressWarnings("unchecked")
    public void onPullEchoes(IoSession session, PullEchoesReq_1501 req) {
        long accountId = ((PlayerSession<Long>) session).getIdentity();
        List<Echo> echoes = echoService.pullEchoes(accountId, req.getOwnerSpaceId());
        EchoListResp_1502.Builder resp = EchoListResp_1502.newBuilder().setCode(0).setMessage("ok");
        for (Echo echo : echoes) {
            resp.addEchoes(EchoSnapshot.newBuilder()
                    .setEchoId(echo.getId())
                    .setFromAccountId(echo.getFromAccountId())
                    .setPayload(echo.getPayload() == null ? "" : echo.getPayload())
                    .setExpireAt(echo.getExpireAt())
                    .build());
        }
        session.send(resp.build());
    }

    @HandlerMethod
    @SuppressWarnings("unchecked")
    public void onLeaveTrace(IoSession session, LeaveTraceReq_1503 req) {
        long accountId = ((PlayerSession<Long>) session).getIdentity();
        try {
            Echo echo = echoService.leaveTrace(
                    accountId, req.getOwnerSpaceId(), req.getPayload(), req.getTtlMillis());
            session.send(LeaveTraceResp_1504.newBuilder()
                    .setCode(0)
                    .setEchoId(echo.getId())
                    .setExpireAt(echo.getExpireAt())
                    .setMessage("ok")
                    .build());
        } catch (IllegalArgumentException e) {
            session.send(LeaveTraceResp_1504.newBuilder()
                    .setCode(1)
                    .setMessage(e.getMessage())
                    .build());
        }
    }
}
