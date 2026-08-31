package com.aengine.persistence.dialect;

import com.aengine.persistence.TypeEnum;
import com.aengine.persistence.annotation.Index;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方言层。重点锁两件事：PG 拼得对，MySQL 遇到不支持的<b>抛而不是降级</b>。
 */
class DialectTest {

    private final PostgresDialect pg = new PostgresDialect();
    private final MySqlDialect mysql = new MySqlDialect();

    // -------------------------------------------------------------- 标识符与类型

    @Test
    void PG用双引号保住驼峰列名() {
        // 不加引号 PG 会把 accountId 折成 accountid，与元数据对不上
        assertEquals("\"accountId\"", pg.quote("accountId"));
        assertEquals("`accountId`", mysql.quote("accountId"));
    }

    @Test
    void 标识符里的引号要转义() {
        assertEquals("\"a\"\"b\"", pg.quote("a\"b"));
        assertEquals("`a``b`", mysql.quote("a`b"));
    }

    @Test
    void 原生类型优先于枚举类型() {
        assertEquals("jsonb", pg.columnType(TypeEnum.TEXT, 255, "jsonb"));
        assertEquals("vector(768)", pg.columnType(TypeEnum.TEXT, 255, "vector(768)"));
        assertEquals("text", pg.columnType(TypeEnum.TEXT, 255, null));
        assertEquals("text", pg.columnType(TypeEnum.TEXT, 255, "  "));
    }

    @Test
    void 两个方言的类型映射不同() {
        assertEquals("integer", pg.columnType(TypeEnum.INT, 0, null));
        assertEquals("int", mysql.columnType(TypeEnum.INT, 0, null));
        assertEquals("bytea", pg.columnType(TypeEnum.BLOB, 0, null));
        assertEquals("blob", mysql.columnType(TypeEnum.BLOB, 0, null));
    }

    @Test
    void 大文本与二进制不给默认值() {
        // 与 TypeEnum.hasDefaultValue 一致：这两类给不了默认值
        assertNull(pg.defaultLiteral(TypeEnum.TEXT));
        assertNull(pg.defaultLiteral(TypeEnum.BLOB));
        assertEquals("0", pg.defaultLiteral(TypeEnum.BIGINT));
        assertEquals("''", pg.defaultLiteral(TypeEnum.VARCHAR));
    }

    // ------------------------------------------------------------------ 索引

    @Test
    void PG局部索引带上WHERE() {
        IndexSpec spec = new IndexSpec("uk_source_card", Index.IndexType.UNIQUE)
                .column("sourceCardId")
                .where("\"sourceCardId\" IS NOT NULL AND \"deletedAt\" IS NULL");
        String sql = pg.createIndex("t_work", spec);
        assertTrue(sql.contains("CREATE UNIQUE INDEX IF NOT EXISTS"));
        assertTrue(sql.contains("\"t_work_uk_source_card\""));
        assertTrue(sql.endsWith("WHERE \"sourceCardId\" IS NOT NULL AND \"deletedAt\" IS NULL"));
    }

    @Test
    void PG索引支持列级降序() {
        IndexSpec spec = new IndexSpec("idx_pub", Index.IndexType.NORMAL)
                .column("publishedAt", IndexSpec.Order.DESC)
                .column("id", IndexSpec.Order.DESC);
        assertTrue(pg.createIndex("t_work", spec).contains("(\"publishedAt\" DESC, \"id\" DESC)"));
    }

    @Test
    void 索引名带表前缀避免跨表撞名() {
        // PG 的索引名在 schema 内唯一，两张表各有 idx_account_id 会撞
        IndexSpec spec = new IndexSpec("idx_account_id", Index.IndexType.NORMAL).column("accountId");
        assertTrue(pg.createIndex("t_pet", spec).contains("\"t_pet_idx_account_id\""));
        assertTrue(pg.createIndex("t_echo", spec).contains("\"t_echo_idx_account_id\""));
    }

    // -------------------------------------------------- 🔴 不支持要抛，不能降级

    @Test
    void MySQL遇到局部索引直接抛() {
        // 🔴 这条是本类最重要的一条。降级成普通索引会让唯一约束静默失效：
        //    索引建出来了、语句成功了，只是它守不住任何东西。
        IndexSpec spec = new IndexSpec("uk", Index.IndexType.UNIQUE)
                .column("sourceCardId").where("\"deletedAt\" IS NULL");
        UnsupportedFeatureException e =
                assertThrows(UnsupportedFeatureException.class, () -> mysql.createIndex("t_work", spec));
        assertEquals(Feature.PARTIAL_INDEX, e.getFeature());
    }

    @Test
    void MySQL遇到降序索引直接抛() {
        // 5.7 解析 DESC 但忽略它，8.0 才真支持。与其赌版本不如显式失败。
        IndexSpec spec = new IndexSpec("idx", Index.IndexType.NORMAL)
                .column("publishedAt", IndexSpec.Order.DESC);
        assertEquals(Feature.INDEX_COLUMN_ORDER,
                assertThrows(UnsupportedFeatureException.class,
                        () -> mysql.createIndex("t_work", spec)).getFeature());
    }

    @Test
    void MySQL遇到jsonb与vector直接抛() {
        assertEquals(Feature.JSONB, assertThrows(UnsupportedFeatureException.class,
                () -> mysql.columnType(TypeEnum.TEXT, 0, "jsonb")).getFeature());
        assertEquals(Feature.VECTOR, assertThrows(UnsupportedFeatureException.class,
                () -> mysql.columnType(TypeEnum.TEXT, 0, "vector(768)")).getFeature());
    }

    @Test
    void 特性开关如实反映两个方言的差距() {
        assertTrue(pg.supports(Feature.PARTIAL_INDEX));
        assertTrue(pg.supports(Feature.VECTOR));
        assertFalse(mysql.supports(Feature.PARTIAL_INDEX));
        assertFalse(mysql.supports(Feature.JSONB));
        assertTrue(mysql.supports(Feature.FOREIGN_KEY));
    }

    // ------------------------------------------------------------- 建表与约束

    @Test
    void 建表带主键与表级约束() {
        String sql = pg.createTable("t_work",
                List.of("\"id\" bigint NOT NULL", "\"status\" varchar(16) NOT NULL DEFAULT ''"),
                List.of("id"),
                List.of(pg.checkConstraint("t_work_ck_status", "\"status\" IN ('draft','public')")));
        assertTrue(sql.startsWith("CREATE TABLE IF NOT EXISTS \"t_work\" ("));
        assertTrue(sql.contains("PRIMARY KEY (\"id\"),"));
        assertTrue(sql.contains("CONSTRAINT \"t_work_ck_status\" CHECK"));
        assertTrue(sql.endsWith(")"));
    }

    @Test
    void 建表无约束时主键后不带逗号() {
        // 多一个逗号就是语法错，而这条路径（无表级约束）是最常见的
        String sql = pg.createTable("t_x", List.of("\"id\" bigint NOT NULL"), List.of("id"), List.of());
        assertFalse(sql.contains("PRIMARY KEY (\"id\"),"));
        assertTrue(sql.contains("PRIMARY KEY (\"id\")\n)"));
    }

    @Test
    void 外键默认RESTRICT() {
        String fk = pg.foreignKey("t_work_fk_author", List.of("authorId"),
                "t_account", List.of("id"), null);
        assertEquals("CONSTRAINT \"t_work_fk_author\" FOREIGN KEY (\"authorId\") "
                + "REFERENCES \"t_account\" (\"id\") ON DELETE RESTRICT", fk);
    }

    // ------------------------------------------------------------- 分页与幂等写

    @Test
    void PG的OFFSET可以独立于LIMIT() {
        assertEquals(" LIMIT 20", pg.limitClause(20, 0));
        assertEquals(" OFFSET 40", pg.limitClause(0, 40));
        assertEquals(" LIMIT 20 OFFSET 40", pg.limitClause(20, 40));
    }

    @Test
    void MySQL的OFFSET必须跟在LIMIT后面() {
        // 单独给 OFFSET 在 MySQL 是语法错，要补一个大 LIMIT
        assertEquals(" LIMIT 20", mysql.limitClause(20, 0));
        assertTrue(mysql.limitClause(0, 40).startsWith(" LIMIT "));
        assertTrue(mysql.limitClause(0, 40).endsWith(" OFFSET 40"));
    }

    @Test
    void 幂等写两个方言语法不同() {
        assertEquals(" ON CONFLICT (\"petId\", \"accountId\") DO UPDATE SET \"createdAt\" = EXCLUDED.\"createdAt\"",
                pg.upsertSuffix(List.of("petId", "accountId"), List.of("createdAt")));
        assertEquals(" ON CONFLICT (\"petId\") DO NOTHING",
                pg.upsertSuffix(List.of("petId"), List.of()));
        assertEquals(" ON DUPLICATE KEY UPDATE `createdAt` = VALUES(`createdAt`)",
                mysql.upsertSuffix(List.of("petId"), List.of("createdAt")));
    }

    @Test
    void MySQL的DoNothing需要一个列名占位() {
        // MySQL 没有 DO NOTHING，用恒等赋值模拟；没有列名时这条语句拼不出来
        assertEquals(" ON DUPLICATE KEY UPDATE `petId` = `petId`",
                mysql.upsertSuffix(List.of("petId"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> mysql.upsertSuffix(List.of(), List.of()));
    }
}
