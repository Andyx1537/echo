package com.aengine.persistence.db;

import java.util.Locale;

/** Repository 启动时对数据库结构采取的策略。 */
public enum SchemaMode {
    /** 不检查也不修改，适合完全由外部迁移工具管理的特殊环境。 */
    NONE,
    /** 只验证表和映射列存在，任何不一致都阻止 Repository 启动。 */
    VALIDATE,
    /** 开发兼容模式：幂等建表并补缺失列/索引。 */
    UPDATE;

    public static SchemaMode parse(String value) {
        if (value == null || value.isBlank()) {
            return UPDATE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported db.schemaMode: " + value
                    + " (expected validate|update|none)", e);
        }
    }
}
