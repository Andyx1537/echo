package com.echo.gateway;

import com.aengine.network.support.HandlerMethod;
import com.aengine.network.support.IPacketHandler;
import com.aengine.network.support.IoSession;
import com.aengine.network.support.PlayerSession;
import com.echo.infra.vector.ScoredId;
import com.echo.module.resonance.ResonanceService;
import com.echo.proto.resonance.QueryResonanceReq_1401;
import com.echo.proto.resonance.ResonanceCandidate;
import com.echo.proto.resonance.ResonanceListResp_1402;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 共鸣匹配协议处理器（TECH-P1 §4.3，号段 14xx）。已登录校验由引擎完成。
 */
@Slf4j
@IPacketHandler
public class ResonanceHandler {

    private final ResonanceService resonanceService;

    public ResonanceHandler(ResonanceService resonanceService) {
        this.resonanceService = resonanceService;
    }

    @HandlerMethod
    @SuppressWarnings("unchecked")
    public void onQueryResonance(IoSession session, QueryResonanceReq_1401 req) {
        long accountId = ((PlayerSession<Long>) session).getIdentity();
        List<ScoredId> candidates = resonanceService.queryResonance(accountId, req.getTopN(), req.getThreshold());
        ResonanceListResp_1402.Builder resp = ResonanceListResp_1402.newBuilder().setCode(0).setMessage("ok");
        for (ScoredId c : candidates) {
            // 对客户端暴露直觉化"共鸣度"(越大越近) = 1 - 余弦距离，裁剪到 [0,1]；
            // 内部排序/阈值/落库仍用余弦距离(越小越近)。
            resp.addCandidates(ResonanceCandidate.newBuilder()
                    .setAccountId(c.accountId())
                    .setScore(toAffinity(c.score()))
                    .build());
        }
        session.send(resp.build());
    }

    /** 余弦距离 → 共鸣度(0~1，越大越相似)。 */
    private static double toAffinity(double cosineDistance) {
        double affinity = 1.0d - cosineDistance;
        if (affinity < 0d) {
            return 0d;
        }
        return Math.min(affinity, 1.0d);
    }
}
