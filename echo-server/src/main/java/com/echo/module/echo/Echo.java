package com.echo.module.echo;

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
 * 回声实体（TECH-P1 §2）。
 *
 * <p>字段：id(pk) / ownerSpaceId / fromAccountId / payload(json) / expireAt。
 * 共鸣者留在某空间里的异步快照/留痕，到期由 scheduler 清理。
 * 建索引：idx(ownerSpaceId) 取某空间的回声、idx(expireAt) 供过期清理范围扫描。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@Table(name = "t_echo", comment = "回声(异步快照/留痕)",
        index = {
                @Index(name = "idx_owner_space_id", columns = {"ownerSpaceId"}),
                @Index(name = "idx_expire_at", columns = {"expireAt"})
        },
        cache = {@Cache(columns = {"ownerSpaceId"})})
public class Echo implements AbstractEntity {

    @Pk(auto = false)
    @Column(name = "id", comment = "回声ID(雪花)")
    private long id;

    /** 所属空间（被渲染进谁的世界）。被缓存，故只读。 */
    @Column(name = "ownerSpaceId", readOnly = true, comment = "所属空间ID")
    private long ownerSpaceId;

    /** 留痕来源账号。 */
    @Column(name = "fromAccountId", comment = "留痕来源账号ID")
    private long fromAccountId;

    /** 回声内容（痕迹/手势/信物），json 文本。 */
    @Column(name = "payload", length = 4096, comment = "回声内容(json)")
    private String payload;

    /** 过期时间（epoch 毫秒），到期清理。 */
    @Column(name = "expireAt", comment = "过期时间(ms)")
    private long expireAt;
}
