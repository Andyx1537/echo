package com.aengine.persistence.dialect;

import com.aengine.persistence.TypeEnum;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PostgreSQL 方言。支持全部 {@link Feature}。
 */
public final class PostgresDialect implements Dialect {

    private static final Set<Feature> SUPPORTED = EnumSet.allOf(Feature.class);

    @Override
    public String name() {
        return "PostgreSQL";
    }

    @Override
    public boolean supports(Feature feature) {
        return SUPPORTED.contains(feature);
    }

    @Override
    public String quote(String identifier) {
        // PG 的双引号标识符区分大小写，而引擎的列名是驼峰（accountId），
        // 不加引号会被折成小写 accountid，与元数据对不上。所以这里不能省。
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    @Override
    public String columnType(TypeEnum type, int length, String nativeType) {
        if (nativeType != null && !nativeType.isBlank()) {
            return nativeType.trim();
        }
        return switch (type) {
            case TINYINT, SMALLINT -> "smallint";
            case INT -> "integer";
            case BIGINT -> "bigint";
            case DOUBLE -> "double precision";
            case CHAR -> "char(" + Math.max(1, length) + ")";
            case VARCHAR -> "varchar(" + Math.max(1, length) + ")";
            case TEXT, OBJ -> "text";
            case DATETIME -> "timestamp";
            case BLOB -> "bytea";
        };
    }

    @Override
    public String defaultLiteral(TypeEnum type) {
        return switch (type) {
            case TINYINT, SMALLINT, INT, BIGINT -> "0";
            case DOUBLE -> "0";
            case CHAR, VARCHAR -> "''";
            case DATETIME -> "'1970-01-01 00:00:00'";
            // TEXT / BLOB / OBJ 不给默认值，与 TypeEnum.hasDefaultValue 一致
            case TEXT, BLOB, OBJ -> null;
        };
    }

    @Override
    public String createTable(String table, List<String> columnDdl, List<String> pkColumns,
                              List<String> constraints) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(quote(table)).append(" (\n");
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
        return "ALTER TABLE " + quote(table) + " ADD COLUMN IF NOT EXISTS " + columnDdl;
    }

    @Override
    public String tableExistsSql() {
        return "SELECT 1 FROM information_schema.tables"
                + " WHERE table_schema = current_schema() AND table_name = ?";
    }

    @Override
    public String columnExistsSql() {
        return "SELECT 1 FROM information_schema.columns"
                + " WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?";
    }

    @Override
    public String createIndex(String table, IndexSpec spec) {
        StringBuilder sb = new StringBuilder("CREATE ");
        if (spec.getType() == com.aengine.persistence.annotation.Index.IndexType.UNIQUE) {
            sb.append("UNIQUE ");
        }
        // 索引名带表前缀：PG 的索引名在 schema 内唯一，不同表同名索引（idx_account_id 一类）会撞。
        sb.append("INDEX IF NOT EXISTS ").append(quote(table + '_' + spec.getName()))
                .append(" ON ").append(quote(table));
        if (!spec.getUsing().isEmpty()) {
            sb.append(" USING ").append(spec.getUsing());
        }
        sb.append(" (");
        sb.append(spec.getColumns().stream()
                .map(c -> quote(c.name()) + switch (c.order()) {
                    case DESC -> " DESC";
                    case ASC -> " ASC";
                    case NONE -> "";
                })
                .collect(Collectors.joining(", ")));
        sb.append(")");
        if (spec.isPartial()) {
            sb.append(" WHERE ").append(spec.getWhere());
        }
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
            sb.append(" OFFSET ").append(offset);
        }
        return sb.toString();
    }

    @Override
    public String upsertSuffix(List<String> conflictColumns, List<String> updateColumns) {
        if (conflictColumns == null || conflictColumns.isEmpty()) {
            return " ON CONFLICT DO NOTHING";
        }
        String target = " ON CONFLICT ("
                + conflictColumns.stream().map(this::quote).collect(Collectors.joining(", ")) + ")";
        if (updateColumns == null || updateColumns.isEmpty()) {
            return target + " DO NOTHING";
        }
        return target + " DO UPDATE SET " + updateColumns.stream()
                .map(c -> quote(c) + " = EXCLUDED." + quote(c))
                .collect(Collectors.joining(", "));
    }
}
