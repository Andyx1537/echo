package com.echo.module.social;

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
 * 摊位实体（TECH-P1 §2，P1 仅占位/展示）。
 *
 * <p>字段：id(pk) / accountId / spaceId / displayPayload(json) / status(占位)。
 * P3 升级为约战入口；当前仅持久化占位与展示内容。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@Table(name = "t_stall", comment = "摊位(P1占位)",
        index = {@Index(name = "idx_account_id", columns = {"accountId"})},
        cache = {@Cache(columns = {"accountId"})})
public class Stall implements AbstractEntity {

    /** 状态：占位中。 */
    public static final int STATUS_PLACEHOLDER = 0;

    @Pk(auto = false)
    @Column(name = "id", comment = "摊位ID(雪花)")
    private long id;

    @Column(name = "accountId", readOnly = true, comment = "归属账号ID")
    private long accountId;

    /** 摊位所在空间。 */
    @Column(name = "spaceId", comment = "所在空间ID")
    private long spaceId;

    /** 展示内容，json 文本。 */
    @Column(name = "displayPayload", length = 2048, comment = "展示内容(json)")
    private String displayPayload;

    /** 状态（占位/启用...）。 */
    @Column(name = "status", comment = "状态(0占位)")
    private int status;
}
