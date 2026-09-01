package com.echo.module.mind;

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
 * 个人向量元数据实体（TECH-P1 §2）。
 *
 * <p>字段：id(pk) / accountId / dim / vectorRef(外部库 key) / normHash。
 * 向量本体落同库 pgvector 列（由 IVectorStore 通道维护），本实体仅存关系元数据，通过 {@code vectorRef} 关联。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@Table(name = "t_self_vector", comment = "个人向量元数据",
        index = {@Index(name = "idx_account_id", columns = {"accountId"})},
        cache = {@Cache(columns = {"accountId"})})
public class SelfVector implements AbstractEntity {

    @Pk(auto = false)
    @Column(name = "id", comment = "向量元数据ID(雪花)")
    private long id;

    @Column(name = "accountId", readOnly = true, comment = "归属账号ID")
    private long accountId;

    /** 向量维度。 */
    @Column(name = "dim", comment = "向量维度")
    private int dim;

    /** 外部向量库中的引用 key。 */
    @Column(name = "vectorRef", length = 128, comment = "外部向量库引用key")
    private String vectorRef;

    /** 归一化哈希，用于幂等/去重校验。 */
    @Column(name = "normHash", length = 64, comment = "归一化哈希")
    private String normHash;

    @Column(name = "embedProvider", length = 32, comment = "嵌入供应商")
    private String embedProvider;

    @Column(name = "embedModel", length = 128, comment = "嵌入模型")
    private String embedModel;

    @Column(name = "embedVersion", length = 64, comment = "嵌入模型版本")
    private String embedVersion;

    @Column(name = "embeddedAt", comment = "最近向量生成时间")
    private long embeddedAt;
}
