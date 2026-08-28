package com.echo.module.space;

import com.aengine.persistence.AbstractEntity;
import com.aengine.persistence.annotation.Cache;
import com.aengine.persistence.annotation.Column;
import com.aengine.persistence.annotation.Index;
import com.aengine.persistence.annotation.Pk;
import com.aengine.persistence.annotation.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 意识空间实例实体（TECH-P1 §2）。
 *
 * <p>字段：id(pk) / accountId / presetSetId / dynamicParams(json) / hostConfig(json) / updateTime。
 * presetSetId 指向定势组合（template 配置）；hostConfig 形如
 * {@code {broadcast,asyncOnly,resonanceThreshold,allowBattle}}。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@Table(name = "t_mind_space", comment = "意识空间实例",
        index = {@Index(name = "idx_account_id", columns = {"accountId"})},
        cache = {@Cache(columns = {"accountId"})})
public class MindSpace implements AbstractEntity {

    @Pk(auto = false)
    @Column(name = "id", comment = "空间ID(雪花)")
    private long id;

    @Column(name = "accountId", readOnly = true, comment = "归属账号ID")
    private long accountId;

    /** 定势组合 ID（template 配置表）。 */
    @Column(name = "presetSetId", comment = "定势组合ID")
    private long presetSetId;

    /** 动态参数（天气/光影/点缀），json 文本。 */
    @Column(name = "dynamicParams", length = 2048, comment = "动态参数(json)")
    private String dynamicParams;

    /** 主控配置，json 文本。 */
    @Column(name = "hostConfig", length = 2048, comment = "主控配置(json)")
    private String hostConfig;

    @Column(name = "updateTime", comment = "更新时间(ms)")
    private long updateTime;
}
