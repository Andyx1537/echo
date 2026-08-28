package com.echo.module.account;

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
 * 账号实体（TECH-P1 §2）。
 *
 * <p>字段：id(pk) / openId / status / createTime。openId 作为外部登录态唯一键，
 * 建唯一索引并缓存（缓存列须 {@code readOnly}，openId 注册后不变，符合约束）。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@Table(name = "t_account", comment = "账号",
        index = {@Index(name = "uk_open_id", columns = {"openId"}, type = Index.IndexType.UNIQUE)},
        cache = {@Cache(columns = {"openId"})})
public class Account implements AbstractEntity {

    /** 雪花 ID 主键（非自增，由 IDGenerator 生成）。 */
    @Pk(auto = false)
    @Column(name = "id", comment = "账号ID(雪花)")
    private long id;

    /** 外部登录态唯一标识（如三方 openId）。被缓存，故只读。 */
    @Column(name = "openId", length = 64, readOnly = true, comment = "外部登录唯一标识")
    private String openId;

    /** 账号状态：0=正常，1=封禁等（业务自定义）。 */
    @Column(name = "status", comment = "账号状态")
    private int status;

    /** 创建时间（epoch 毫秒），只读。 */
    @Column(name = "createTime", readOnly = true, comment = "创建时间(ms)")
    private long createTime;
}
