package com.echo.module.space;

import com.aengine.util.GsonUtil;
import com.aengine.util.id.IDGenerator;
import com.echo.module.mind.SelfVector;
import com.echo.module.mind.SelfVectorRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * 意识空间领域服务（TECH-P1 §4.2）：取 SelfVector → 选定势 → 生成/复用 MindSpace → 下发快照。
 *
 * <p>客户端按 {@code presetSetId + dynamicParams} 本地拼装渲染，服务端不传大资源。</p>
 */
@Slf4j
public class MindSpaceService {

    /** 动态参数占位默认值（天气/光影/点缀），受预算上限约束，后续细化。 */
    private static final String DEFAULT_DYNAMIC_PARAMS =
            "{\"weather\":\"dusk\",\"light\":\"warm\",\"dust\":true}";

    private final MindSpaceRepository mindSpaceRepository;

    private final SelfVectorRepository selfVectorRepository;

    private final IDGenerator idGenerator;

    public MindSpaceService(MindSpaceRepository mindSpaceRepository,
                            SelfVectorRepository selfVectorRepository,
                            IDGenerator idGenerator) {
        this.mindSpaceRepository = mindSpaceRepository;
        this.selfVectorRepository = selfVectorRepository;
        this.idGenerator = idGenerator;
    }

    /**
     * 进入（自己的）意识空间：已有则复用，否则按个人向量挑定势新建。
     *
     * @param accountId 已登录账号 ID
     * @return 空间实例（含 presetSetId / dynamicParams / hostConfig）
     */
    public MindSpace enterSpace(long accountId) {
        MindSpace space = mindSpaceRepository.get("accountId", accountId);
        if (space != null) {
            return space;
        }
        SelfVector selfVector = selfVectorRepository.get("accountId", accountId);
        String normHash = selfVector == null ? null : selfVector.getNormHash();
        long presetSetId = PresetCatalog.choosePreset(normHash);

        space = new MindSpace();
        space.setId(idGenerator.nextId());
        space.setAccountId(accountId);
        space.setPresetSetId(presetSetId);
        space.setDynamicParams(DEFAULT_DYNAMIC_PARAMS);
        space.setHostConfig(GsonUtil.beanToJson(HostConfig.defaults()));
        space.setUpdateTime(System.currentTimeMillis());
        mindSpaceRepository.add(space);
        log.info("新建意识空间 accountId={}, spaceId={}, presetSetId={}", accountId, space.getId(), presetSetId);
        return space;
    }

    /**
     * 更新主控配置。空间不存在时先创建默认空间再更新。
     *
     * @param accountId  已登录账号 ID
     * @param hostConfig 新主控配置
     * @return 更新后的空间
     */
    public MindSpace updateHostConfig(long accountId, HostConfig hostConfig) {
        MindSpace space = mindSpaceRepository.get("accountId", accountId);
        if (space == null) {
            space = enterSpace(accountId);
        }
        space.setHostConfig(GsonUtil.beanToJson(hostConfig));
        space.setUpdateTime(System.currentTimeMillis());
        mindSpaceRepository.save(space);
        log.info("更新主控配置 accountId={}, spaceId={}, hostConfig={}",
                accountId, space.getId(), space.getHostConfig());
        return space;
    }
}
