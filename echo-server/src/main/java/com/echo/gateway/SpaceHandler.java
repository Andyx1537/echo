package com.echo.gateway;

import com.aengine.network.support.HandlerMethod;
import com.aengine.network.support.IPacketHandler;
import com.aengine.network.support.IoSession;
import com.aengine.network.support.PlayerSession;
import com.echo.module.space.HostConfig;
import com.echo.module.space.MindSpace;
import com.echo.module.space.MindSpaceService;
import com.echo.proto.space.EnterSpaceReq_1301;
import com.echo.proto.space.SpaceSnapshotResp_1302;
import com.echo.proto.space.UpdateHostConfigReq_1303;
import com.echo.proto.space.UpdateHostConfigResp_1304;
import lombok.extern.slf4j.Slf4j;

/**
 * 意识空间协议处理器（TECH-P1 §4.2，号段 13xx）。已登录校验由引擎完成。
 */
@Slf4j
@IPacketHandler
public class SpaceHandler {

    private final MindSpaceService mindSpaceService;

    public SpaceHandler(MindSpaceService mindSpaceService) {
        this.mindSpaceService = mindSpaceService;
    }

    @HandlerMethod
    @SuppressWarnings("unchecked")
    public void onEnterSpace(IoSession session, EnterSpaceReq_1301 req) {
        long accountId = ((PlayerSession<Long>) session).getIdentity();
        MindSpace space = mindSpaceService.enterSpace(accountId);
        session.send(SpaceSnapshotResp_1302.newBuilder()
                .setCode(0)
                .setSpaceId(space.getId())
                .setPresetSetId(space.getPresetSetId())
                .setDynamicParams(nullToEmpty(space.getDynamicParams()))
                .setHostConfig(nullToEmpty(space.getHostConfig()))
                .setMessage("ok")
                .build());
    }

    @HandlerMethod
    @SuppressWarnings("unchecked")
    public void onUpdateHostConfig(IoSession session, UpdateHostConfigReq_1303 req) {
        long accountId = ((PlayerSession<Long>) session).getIdentity();
        HostConfig hostConfig = new HostConfig(
                req.getBroadcast(), req.getAsyncOnly(), req.getResonanceThreshold(), req.getAllowBattle());
        MindSpace space = mindSpaceService.updateHostConfig(accountId, hostConfig);
        session.send(UpdateHostConfigResp_1304.newBuilder()
                .setCode(0)
                .setHostConfig(nullToEmpty(space.getHostConfig()))
                .setMessage("ok")
                .build());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
