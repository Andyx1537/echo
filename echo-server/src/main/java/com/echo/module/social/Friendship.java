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
 * 关系链实体（TECH-P1 §2）。
 *
 * <p>字段：id(pk) / accountId / peerId / type(friend/follow) / createTime。
 * 复合唯一索引 (accountId,peerId) 防重复关系；另建 accountId 索引/缓存以"列出我的关系"。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@Table(name = "t_friendship", comment = "关系链(好友/关注)",
        index = {
                @Index(name = "uk_account_peer", columns = {"accountId", "peerId"}, type = Index.IndexType.UNIQUE),
                @Index(name = "idx_account_id", columns = {"accountId"})
        },
        cache = {@Cache(columns = {"accountId"})})
public class Friendship implements AbstractEntity {

    /** 关系类型：好友。 */
    public static final int TYPE_FRIEND = 0;
    /** 关系类型：关注。 */
    public static final int TYPE_FOLLOW = 1;

    @Pk(auto = false)
    @Column(name = "id", comment = "关系ID(雪花)")
    private long id;

    /** 关系发起方。被缓存，故只读。 */
    @Column(name = "accountId", readOnly = true, comment = "发起方账号ID")
    private long accountId;

    /** 关系对端。复合索引成员，关系对不可变，只读。 */
    @Column(name = "peerId", readOnly = true, comment = "对端账号ID")
    private long peerId;

    /** 关系类型：0=好友，1=关注。 */
    @Column(name = "type", comment = "关系类型(0好友/1关注)")
    private int type;

    @Column(name = "createTime", readOnly = true, comment = "创建时间(ms)")
    private long createTime;
}
