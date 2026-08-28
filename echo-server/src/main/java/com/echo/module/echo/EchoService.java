package com.echo.module.echo;

import com.aengine.util.GsonUtil;
import com.aengine.util.id.IDGenerator;
import com.echo.module.space.HostConfig;
import com.echo.module.space.MindSpace;
import com.echo.module.space.MindSpaceRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 回声领域服务（TECH-P1 §4.3，P1 异步）：拉取/留痕 + 过期清理。
 */
@Slf4j
public class EchoService {

    /** 默认存活时长：7 天。 */
    public static final long DEFAULT_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private final EchoRepository echoRepository;

    private final MindSpaceRepository mindSpaceRepository;

    private final IDGenerator idGenerator;

    public EchoService(EchoRepository echoRepository,
                       MindSpaceRepository mindSpaceRepository,
                       IDGenerator idGenerator) {
        this.echoRepository = echoRepository;
        this.mindSpaceRepository = mindSpaceRepository;
        this.idGenerator = idGenerator;
    }

    /**
     * 拉取某空间下满足 hostConfig 且未过期的回声快照。
     *
     * @param viewerAccountId 拉取者账号 ID
     * @param ownerSpaceId    目标空间 ID（MindSpace.id）
     * @return 未过期回声列表（空间不存在或被 hostConfig 拒绝时为空）
     */
    public List<Echo> pullEchoes(long viewerAccountId, long ownerSpaceId) {
        MindSpace space = mindSpaceRepository.get(ownerSpaceId);
        if (space == null) {
            return List.of();
        }
        if (!visibleTo(space, viewerAccountId)) {
            log.debug("空间 {} 的 hostConfig 不允许 {} 拉取回声", ownerSpaceId, viewerAccountId);
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<Echo> echoes = echoRepository.list("ownerSpaceId", ownerSpaceId);
        List<Echo> alive = new ArrayList<>();
        for (Echo echo : echoes) {
            if (echo.getExpireAt() > now) {
                alive.add(echo);
            }
        }
        return alive;
    }

    /**
     * 在某空间留痕（生成回声落库，对共鸣者世界可见）。
     *
     * @param fromAccountId 留痕来源账号
     * @param ownerSpaceId  目标空间 ID
     * @param payload       回声内容(json)
     * @param ttlMillis     存活时长(ms, &lt;=0 用默认)
     * @return 落库的回声
     */
    public Echo leaveTrace(long fromAccountId, long ownerSpaceId, String payload, long ttlMillis) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload 不能为空");
        }
        long ttl = ttlMillis > 0 ? ttlMillis : DEFAULT_TTL_MILLIS;
        Echo echo = new Echo();
        echo.setId(idGenerator.nextId());
        echo.setOwnerSpaceId(ownerSpaceId);
        echo.setFromAccountId(fromAccountId);
        echo.setPayload(payload);
        echo.setExpireAt(System.currentTimeMillis() + ttl);
        echoRepository.add(echo);
        log.info("留痕 fromAccountId={}, ownerSpaceId={}, echoId={}, expireAt={}",
                fromAccountId, ownerSpaceId, echo.getId(), echo.getExpireAt());
        return echo;
    }

    /**
     * 清理过期回声（由 scheduler Cron 定时调用）。
     *
     * @return 删除行数
     */
    public int purgeExpired() {
        int n = echoRepository.removeExpired(System.currentTimeMillis());
        if (n > 0) {
            log.info("清理过期回声 {} 条", n);
        }
        return n;
    }

    /** hostConfig 可见性：非广播空间仅空间主人自己可见（P1 简化规则）。 */
    private boolean visibleTo(MindSpace space, long viewerAccountId) {
        if (space.getAccountId() == viewerAccountId) {
            return true;
        }
        HostConfig hostConfig = parseHostConfig(space.getHostConfig());
        return hostConfig.isBroadcast();
    }

    private HostConfig parseHostConfig(String json) {
        if (json == null || json.isBlank()) {
            return HostConfig.defaults();
        }
        try {
            HostConfig hostConfig = GsonUtil.jsonToBean(json, HostConfig.class);
            return hostConfig == null ? HostConfig.defaults() : hostConfig;
        } catch (Exception e) {
            log.warn("解析 hostConfig 失败，用默认值: {}", json, e);
            return HostConfig.defaults();
        }
    }
}
