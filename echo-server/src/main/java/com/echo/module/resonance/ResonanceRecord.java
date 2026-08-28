package com.echo.module.resonance;

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
 * 共鸣记录实体（TECH-P1 §2）。
 *
 * <p>字段：id(pk) / accountId / peerId / score / createTime。
 * 记录某账号与共鸣者(peer)的相似度得分。</p>
 *
 * @deprecated 零消费方。写入路径已从 {@link ResonanceService} 移除
 *         （TECH-DESIGN-feed-recall-and-exposure §2.5.3 方案 A），本类与
 *         {@link ResonanceRecordRepository} 仅为兼容保留，表 {@code t_resonance_record}
 *         下个版本 drop。需要精排留痕请走 {@code SPEC-recommendation-ranking §5.4} 的影子日志。
 */
@Deprecated(since = "0.1.0", forRemoval = true)
@Getter
@Setter
@ToString
@NoArgsConstructor
@Table(name = "t_resonance_record", comment = "共鸣记录",
        index = {@Index(name = "idx_account_id", columns = {"accountId"})},
        cache = {@Cache(columns = {"accountId"})})
public class ResonanceRecord implements AbstractEntity {

    @Pk(auto = false)
    @Column(name = "id", comment = "记录ID(雪花)")
    private long id;

    @Column(name = "accountId", readOnly = true, comment = "归属账号ID")
    private long accountId;

    /** 共鸣对端账号 ID。 */
    @Column(name = "peerId", comment = "共鸣对端账号ID")
    private long peerId;

    /** 相似度得分。 */
    @Column(name = "score", comment = "相似度得分")
    private double score;

    @Column(name = "createTime", readOnly = true, comment = "创建时间(ms)")
    private long createTime;
}
