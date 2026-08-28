package com.echo.gateway;

import com.aengine.network.support.HandlerMethod;
import com.aengine.network.support.IPacketHandler;
import com.aengine.network.support.IoSession;
import com.aengine.network.support.PlayerSession;
import com.echo.module.mind.MindProfileService;
import com.echo.proto.mind.MindProfileResp_1202;
import com.echo.proto.mind.SubmitPrefsReq_1201;
import lombok.extern.slf4j.Slf4j;

/**
 * 意识档案协议处理器（TECH-P1 §4.1，号段 12xx）。
 *
 * <p>非登录/心跳的业务消息：不进 {@code noNeedCheckMessage} 白名单，依赖
 * {@code PacketHandlerManager} 的会话校验（{@code session.getIdenty() != null}）。</p>
 */
@Slf4j
@IPacketHandler
public class MindProfileHandler {

    private final MindProfileService mindProfileService;

    public MindProfileHandler(MindProfileService mindProfileService) {
        this.mindProfileService = mindProfileService;
    }

    @HandlerMethod
    @SuppressWarnings("unchecked")
    public void onSubmitPrefs(IoSession session, SubmitPrefsReq_1201 req) {
        long accountId = ((PlayerSession<Long>) session).getIdentity();
        MindProfileService.Outcome outcome =
                mindProfileService.submitPrefs(accountId, req.getRawPrefsList());
        session.send(MindProfileResp_1202.newBuilder()
                .setCode(outcome.code())
                .setProfileId(outcome.profileId())
                .setVectorId(outcome.vectorId())
                .setEnrichedPrefs(outcome.enrichedPrefs() == null ? "" : outcome.enrichedPrefs())
                .setMessage(outcome.message())
                .build());
    }
}
