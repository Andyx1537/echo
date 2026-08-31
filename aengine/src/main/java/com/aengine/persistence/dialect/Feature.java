package com.aengine.persistence.dialect;

/**
 * 方言特性开关。
 *
 * <p>引擎要同时支持 PostgreSQL 与 MySQL，而两者的能力并不对等：局部索引、jsonb、
 * 向量列都是 PG 独有。用显式枚举把差异摆到台面上，而不是让不支持的语法在
 * 拼串时静悄悄产出一条跑不通的 SQL。</p>
 *
 * <h2>🔴 不支持时必须抛，不能降级</h2>
 *
 * <p>典型的错误处理是"这个方言不支持局部索引，那就建成普通索引吧"。
 * <b>那会让唯一约束从"数据库保证"退化成"什么都不保证"，而且不报错</b>——
 * 索引建出来了、语句成功了，只是它守不住任何东西。所以
 * {@link Dialect} 遇到不支持的特性一律抛 {@link UnsupportedFeatureException}。</p>
 */
public enum Feature {

    /** 局部索引（{@code CREATE INDEX ... WHERE ...}）。软删唯一约束靠它。 */
    PARTIAL_INDEX,

    /** 索引内列级排序（{@code ... ("publishedAt" DESC)}）。倒序翻页靠它。 */
    INDEX_COLUMN_ORDER,

    /** 表级 {@code CHECK} 约束。 */
    CHECK_CONSTRAINT,

    /** 真外键约束（不是"外键索引"）。 */
    FOREIGN_KEY,

    /** {@code jsonb} 列类型。 */
    JSONB,

    /** 向量列与相似度检索（pgvector）。 */
    VECTOR,

    /** 幂等写（PG {@code ON CONFLICT} / MySQL {@code ON DUPLICATE KEY UPDATE}）。 */
    UPSERT,

    /** {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS}。MySQL 没有，要先查再加。 */
    ADD_COLUMN_IF_NOT_EXISTS,
}
