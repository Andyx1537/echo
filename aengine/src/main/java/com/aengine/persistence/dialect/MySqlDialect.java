package com.aengine.persistence.dialect;

import com.aengine.persistence.TypeEnum;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MySQL 方言。对齐引擎原有的 {@code JDBCRepository} 行为。
 *
 * <h2>🔴 不支持的四项会抛，不会降级</h2>
 *
 * <p>局部索引、索引列级排序（8.0 之前解析但忽略）、jsonb、向量列，
 * MySQL 都没有。遇到就抛 {@link UnsupportedFeatureException}——
 * 因为这四项承载的都是<b>约束语义</b>，悄悄建成普通索引等于约束消失而没人知道。</p>
 */
public final class MySqlDialect implements Dialect {

    private static final Set<Feature> SUPPORTED = EnumSet.of(
            Feature.CHECK_CONSTRAINT,   // 8.0.16+ 才真正生效，更早的版本解析后忽略
            Feature.FOREIGN_KEY,
            Feature.UPSERT
    );

    @Override
    public String name() {
        return "MySQL";
    }

    @Override
    public boolean supports(Feature feature) {
        return SUPPORTED.contains(feature);
    }

    @Override
    public String quote(String identifier) {
        return '`' + identifier.replace("`", "``") + '`';
    }

    @Override
    public String columnType(TypeEnum type, int length, String nativeType) {
        if (nativeType != null && !nativeType.isBlank()) {
            String t = nativeType.trim().toLowerCase();
            if (t.startsWith("jsonb")) {
                throw new UnsupportedFeatureException(name(), Feature.JSONB, "列类型 " + nativeType);
            }
            if (t.startsWith("vector")) {
                throw new UnsupportedFeatureException(name(), Feature.VECTOR, "列类型 " + nativeType);
            }
            return nativeType.trim();
        }
        return switch (type) {
            case TINYINT -> "tinyint";
            case SMALLINT -> "smallint";
            case INT -> "int";
            case BIGINT -> "bigint";
            case DOUBLE -> "double";
            case CHAR -> "char(" + Math.max(1, length) + ")";
            case VARCHAR -> "varchar(" + Math.max(1, length) + ")";
            case TEXT, OBJ -> "text";
            case DATETIME -> "datetime";
            case BLOB -> "blob";
        };
    }

    @Override
    public String defaultLiteral(TypeEnum type) {
        return switch (type) {
            case TINYINT, SMALLINT, INT, BIGINT, DOUBLE -> "0";
            case CHAR, VARCHAR -> "''";
            case DATETIME -> "'1970-01-01 00:00:00'";
            case TEXT, BLOB, OBJ -> null;
        };
    }

    @Override
    public String createTable(String table, List<String> columnDdl, List<String> pkColumns,
                              List<String> constraints) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(quote(table)).append("(\n");
        for (String col : columnDdl) {
            sb.append("  ").append(col).append(",\n");
        }
        if (pkColumns != null && !pkColumns.isEmpty()) {
            sb.append("  PRIMARY KEY (")
                    .append(pkColumns.stream().map(this::quote).collect(Collectors.joining(", ")))
                    .append(")");
            sb.append(constraints == null || constraints.isEmpty() ? "\n" : ",\n");
        }
        if (constraints != null && !constraints.isEmpty()) {
            for (int i = 0; i < constraints.size(); i++) {
                sb.append("  ").append(constraints.get(i));
                sb.append(i < constraints.size() - 1 ? ",\n" : "\n");
            }
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public String addColumn(String table, String columnDdl) {
        // MySQL 没有 ADD COLUMN IF NOT EXISTS，调用方必须先用 columnExistsSql 判过
        return "ALTER TABLE " + quote(table) + " ADD COLUMN " + columnDdl;
    }

    @Override
    public String tableExistsSql() {
        return "SELECT 1 FROM information_schema.tables"
                + " WHERE table_schema = DATABASE() AND table_name = ?";
    }

    @Override
    public String columnExistsSql() {
        return "SELECT 1 FROM information_schema.columns"
                + " WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
    }

    @Override
    public String createIndex(String table, IndexSpec spec) {
        if (spec.isPartial()) {
            require(Feature.PARTIAL_INDEX, "索引 " + table + '.' + spec.getName());
        }
        if (spec.hasOrder()) {
            // MySQL 5.7 解析 DESC 但忽略它，8.0 才真正支持降序索引。
            // 与其赌版本，不如让它显式失败。
            require(Feature.INDEX_COLUMN_ORDER, "索引 " + table + '.' + spec.getName());
        }
        StringBuilder sb = new StringBuilder("CREATE ");
        if (spec.getType() == com.aengine.persistence.annotation.Index.IndexType.UNIQUE) {
            sb.append("UNIQUE ");
        } else if (spec.getType() == com.aengine.persistence.annotation.Index.IndexType.FULLTEXT) {
            sb.append("FULLTEXT ");
        }
        sb.append("INDEX ").append(quote(spec.getName()))
                .append(" ON ").append(quote(table)).append(" (")
                .append(spec.getColumns().stream().map(c -> quote(c.name()))
                        .collect(Collectors.joining(", ")))
                .append(")");
        return sb.toString();
    }

    @Override
    public String checkConstraint(String name, String expression) {
        return "CONSTRAINT " + quote(name) + " CHECK (" + expression + ")";
    }

    @Override
    public String foreignKey(String name, List<String> columns, String refTable,
                             List<String> refColumns, String onDelete) {
        String action = onDelete == null || onDelete.isBlank() ? "RESTRICT" : onDelete.trim().toUpperCase();
        return "CONSTRAINT " + quote(name) + " FOREIGN KEY ("
                + columns.stream().map(this::quote).collect(Collectors.joining(", "))
                + ") REFERENCES " + quote(refTable) + " ("
                + refColumns.stream().map(this::quote).collect(Collectors.joining(", "))
                + ") ON DELETE " + action;
    }

    @Override
    public String limitClause(int limit, int offset) {
        StringBuilder sb = new StringBuilder();
        if (limit > 0) {
            sb.append(" LIMIT ").append(limit);
        }
        if (offset > 0) {
            // MySQL 的 OFFSET 必须跟在 LIMIT 后面，单独给 OFFSET 是语法错。
            if (limit <= 0) {
                sb.append(" LIMIT ").append(Integer.MAX_VALUE);
            }
            sb.append(" OFFSET ").append(offset);
        }
        return sb.toString();
    }

    @Override
    public String upsertSuffix(List<String> conflictColumns, List<String> updateColumns) {
        // MySQL 的 ON DUPLICATE KEY 不指定冲突列，由表上的唯一键决定，
        // 所以 conflictColumns 在这里只用于判断"是不是要 DO NOTHING"。
        if (updateColumns == null || updateColumns.isEmpty()) {
            // 没有可更新列时用一句恒等赋值占位——MySQL 没有 DO NOTHING。
            // 必须挑一个真实存在的列，否则语法不成立；用冲突列的第一个。
            if (conflictColumns == null || conflictColumns.isEmpty()) {
                throw new IllegalArgumentException(
                        "MySQL 的 DO NOTHING 需要至少一个列名做恒等赋值，conflictColumns 不能为空");
            }
            String c = quote(conflictColumns.get(0));
            return " ON DUPLICATE KEY UPDATE " + c + " = " + c;
        }
        return " ON DUPLICATE KEY UPDATE " + updateColumns.stream()
                .map(c -> quote(c) + " = VALUES(" + quote(c) + ")")
                .collect(Collectors.joining(", "));
    }
}
