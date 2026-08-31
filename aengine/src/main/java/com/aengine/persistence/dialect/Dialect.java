package com.aengine.persistence.dialect;

import com.aengine.persistence.TypeEnum;

import java.util.List;

/**
 * SQL 方言。引擎所有 SQL 都经由方言产出，应用层不写 SQL 字符串。
 *
 * <h2>为什么要有这一层</h2>
 *
 * <p>引擎原本是 MySQL 的（{@code JDBCRepository} 用反引号、{@code TINYINT} 表布尔、
 * {@code DATETIME} 表时间），而 Echo 跑在 PostgreSQL 上，此前是在应用侧另抄了一份
 * {@code PgRepository}。抄一份的后果是<b>两份实现会各自漂移</b>，
 * 而没有任何检查会发现它们不一致。</p>
 *
 * <h2>🔴 本接口只产出 SQL 文本，不碰连接、不碰反射</h2>
 *
 * <p>这样它可以脱离数据库单测——「这个方言拼出来的建表语句长什么样」是个纯函数问题。
 * 拼串逻辑一旦和 JDBC 纠缠，就只能起库才能验，而实际上没人会为了改一个默认值去起库，
 * 于是就不验了。</p>
 */
public interface Dialect {

    /** 方言名，只用于日志与异常文案。 */
    String name();

    /** 是否支持某项特性。不支持时调用方应当抛 {@link UnsupportedFeatureException}，不要降级。 */
    boolean supports(Feature feature);

    /**
     * 断言支持，不支持就抛。
     *
     * @param context 出现位置（表名 / 索引名一类），进异常文案，便于定位
     */
    default void require(Feature feature, String context) {
        if (!supports(feature)) {
            throw new UnsupportedFeatureException(name(), feature, context);
        }
    }

    // ------------------------------------------------------------- 标识符与类型

    /** 给标识符加引号（PG 双引号 / MySQL 反引号）。 */
    String quote(String identifier);

    /**
     * 列类型文本。
     *
     * @param type       引擎的类型枚举
     * @param length     字符串长度，非字符串类型忽略
     * @param nativeType 原生类型直通（{@code jsonb} / {@code vector(768)} 等）。
     *                   <b>非空时优先于 {@code type}</b>——这是让 PG 独有类型进得来的口子，
     *                   方言负责校验自己支不支持
     */
    String columnType(TypeEnum type, int length, String nativeType);

    /**
     * 非空列的缺省默认值字面量；返回 {@code null} 表示该类型不给默认值。
     *
     * <p>存在的理由见 {@link TypeEnum} 类注释：代码回滚时旧代码不会给新列赋值，
     * NOT NULL 无默认值会让插入直接失败。</p>
     */
    String defaultLiteral(TypeEnum type);

    // ------------------------------------------------------------------- DDL

    /**
     * 建表。
     *
     * @param columnDdl   列定义片段，已含类型与 NOT NULL / DEFAULT
     * @param pkColumns   主键列名
     * @param constraints 表级约束片段（CHECK / FOREIGN KEY），可空
     */
    String createTable(String table, List<String> columnDdl, List<String> pkColumns,
                       List<String> constraints);

    /** 加列。MySQL 不支持 IF NOT EXISTS，调用方需先用 {@link #columnExistsSql()} 判断。 */
    String addColumn(String table, String columnDdl);

    /** 查表是否存在。占位符顺序：表名。 */
    String tableExistsSql();

    /** 查列是否存在。占位符顺序：表名、列名。 */
    String columnExistsSql();

    /** 建索引。局部条件与列级排序不被支持时抛 {@link UnsupportedFeatureException}。 */
    String createIndex(String table, IndexSpec spec);

    /** 表级 CHECK 约束片段。 */
    String checkConstraint(String name, String expression);

    /**
     * 表级外键约束片段。
     *
     * @param onDelete {@code RESTRICT} / {@code CASCADE} / {@code SET NULL}，空则用方言默认
     */
    String foreignKey(String name, List<String> columns, String refTable,
                      List<String> refColumns, String onDelete);

    // ------------------------------------------------------------------- DML

    /** 分页片段。{@code offset <= 0} 时不产出 OFFSET。 */
    String limitClause(int limit, int offset);

    /**
     * 幂等写的尾巴（不含 INSERT 主体）。
     *
     * @param conflictColumns 冲突判定列；空表示"冲突就什么都不做"
     * @param updateColumns   冲突时要更新的列；空表示 DO NOTHING
     */
    String upsertSuffix(List<String> conflictColumns, List<String> updateColumns);
}
