package com.echo.module.avatar;

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
 * 形象实体（TECH-P1 §2）。
 *
 * <p>字段：id(pk) / accountId / parts(json) / fashionSlots(json) / updateTime。
 * json 字段以 String 落库为 TEXT；按 accountId 建索引并缓存（accountId 只读）。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@Table(name = "t_avatar", comment = "形象",
        index = {@Index(name = "idx_account_id", columns = {"accountId"})},
        cache = {@Cache(columns = {"accountId"})})
public class Avatar implements AbstractEntity {

    @Pk(auto = false)
    @Column(name = "id", comment = "形象ID(雪花)")
    private long id;

    /** 归属账号。被缓存，故只读。 */
    @Column(name = "accountId", readOnly = true, comment = "归属账号ID")
    private long accountId;

    /** 部位组合（发型/脸/体型...），json 文本。 */
    @Column(name = "parts", length = 2048, comment = "部位组合(json)")
    private String parts;

    /** 时装槽位，json 文本。 */
    @Column(name = "fashionSlots", length = 2048, comment = "时装槽位(json)")
    private String fashionSlots;

    /** 更新时间（epoch 毫秒）。 */
    @Column(name = "updateTime", comment = "更新时间(ms)")
    private long updateTime;
}
