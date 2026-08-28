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
 * 意识档案实体（TECH-P1 §2）。
 *
 * <p>字段：id(pk) / accountId / rawPrefs(json) / enrichedPrefs(json) / vectorId / version。
 * rawPrefs 为用户自拟偏好，enrichedPrefs 为 LLM 补全结果；vectorId 关联 {@code SelfVector}。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@Table(name = "t_mind_profile", comment = "意识档案",
        index = {@Index(name = "idx_account_id", columns = {"accountId"})},
        cache = {@Cache(columns = {"accountId"})})
public class MindProfile implements AbstractEntity {

    @Pk(auto = false)
    @Column(name = "id", comment = "意识档案ID(雪花)")
    private long id;

    @Column(name = "accountId", readOnly = true, comment = "归属账号ID")
    private long accountId;

    /** 用户自拟原始偏好，json 文本。 */
    @Column(name = "rawPrefs", length = 4096, comment = "原始偏好(json)")
    private String rawPrefs;

    /** LLM 补全后的结构化偏好，json 文本。 */
    @Column(name = "enrichedPrefs", length = 4096, comment = "LLM补全偏好(json)")
    private String enrichedPrefs;

    /** 关联的个人向量 ID（SelfVector.id）。 */
    @Column(name = "vectorId", comment = "关联SelfVector的ID")
    private long vectorId;

    /** 档案版本号（偏好/向量演进时自增）。 */
    @Column(name = "version", comment = "版本号")
    private int version;
}
