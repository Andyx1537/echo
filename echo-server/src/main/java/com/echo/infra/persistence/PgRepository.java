package com.echo.infra.persistence;

import com.aengine.persistence.AbstractEntity;
import com.aengine.persistence.ColumnMeta;
import com.aengine.persistence.IRepository;
import com.aengine.persistence.IndexMeta;
import com.aengine.persistence.TableMeta;
import com.aengine.persistence.TypeEnum;
import com.aengine.persistence.annotation.CRepository;
import com.aengine.persistence.annotation.Index;
import com.aengine.persistence.annotation.Table;
import com.aengine.util.GsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.ParameterizedType;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL 方言仓储（实现 Aengine 方言无关的 {@link IRepository}）。
 *
 * <p>以 Aengine MySQL 版 {@code JDBCRepository} 为模板镜像而来，关键差异：</p>
 * <ul>
 *   <li>标识符用双引号 {@code "tbl"."col"}（非反引号）。</li>
 *   <li>建表存在性判断走 {@code information_schema.tables}（非 {@code SHOW TABLES}）。</li>
 *   <li>{@code INSERT INTO ... VALUES (...)}（非 MySQL 的 {@code VALUE}）。</li>
 *   <li>列类型由 {@link TypeEnum} 映射到 PG 类型（不调用 {@code ColumnMeta.buildCreateSQL()}）。</li>
 *   <li>主键用应用层雪花 ID 赋值，{@code @Pk} 非自增；插入前需先 set id（无 RETURNING）。</li>
 * </ul>
 */
@Slf4j
public abstract class PgRepository<T extends AbstractEntity> implements IRepository<T> {

    protected final PgDb db;

    protected final TableMeta meta;

    protected PgRepository() {
        CRepository repository = getAnnotation();
        this.db = PgDbManager.getInstance().get(repository.source());
        if (this.db == null) {
            throw new IllegalStateException("PG 数据源未注册: " + repository.source()
                    + "（需先在 EchoDatabase 初始化后再实例化仓储）");
        }
        // autoCacheIndex=true：与 MySQL 版一致，索引自动生成缓存，供 CachedPgRepository 命中
        this.meta = TableMeta.parse(getClassOfEntity(), true);
        Table table = getClassOfEntity().getAnnotation(Table.class);
        if (table != null && table.autoCreate()) {
            try {
                fixTable();
            } catch (Exception e) {
                log.error("fix table failed: {}", meta.getName(), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected Class<T> getClassOfEntity() {
        return (Class<T>) ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
    }

    // ----------------------------------------------------------------- DDL

    /** 把 {@link TypeEnum} 映射为 PostgreSQL 列类型。 */
    protected String pgType(ColumnMeta col) {
        switch (col.getType()) {
            case INT:
                return "integer";
            case BIGINT:
                return "bigint";
            case SMALLINT:
            case TINYINT:
                return "smallint";
            case DOUBLE:
                return "double precision";
            case CHAR:
            case VARCHAR:
                return "varchar(" + col.getLength() + ")";
            case DATETIME:
                return "timestamp";
            case BLOB:
                return "bytea";
            case TEXT:
            case OBJ:
            default:
                return "text";
        }
    }

    /** 数值/字符/时间类型的 PG 默认值字面量；不可设默认（text/bytea）时返回 null。 */
    private String pgDefault(TypeEnum type) {
        switch (type) {
            case INT:
            case BIGINT:
            case SMALLINT:
            case TINYINT:
            case DOUBLE:
                return "0";
            case CHAR:
            case VARCHAR:
                return "''";
            case DATETIME:
                return "'1970-01-01 00:00:00'";
            default:
                return null;
        }
    }

    private String columnDDL(ColumnMeta col) {
        StringBuilder sb = new StringBuilder();
        sb.append('"').append(col.getName()).append('"').append(' ').append(pgType(col));
        if (col.isNotNull()) {
            sb.append(" NOT NULL");
            if (!col.isPk()) {
                String def = pgDefault(col.getType());
                if (def != null) {
                    sb.append(" DEFAULT ").append(def);
                }
            }
        }
        return sb.toString();
    }

    private String buildCreateTableSQL() {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS \"").append(meta.getName()).append("\" (\n");
        for (ColumnMeta col : meta.getColumns()) {
            sb.append("  ").append(columnDDL(col)).append(",\n");
        }
        sb.append("  PRIMARY KEY (\"").append(meta.getPk().getName()).append("\")\n)");
        return sb.toString();
    }

    private String buildCreateIndexSQL(IndexMeta index) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE ");
        if (index.getType() == Index.IndexType.UNIQUE) {
            sb.append("UNIQUE ");
        }
        // 索引名加表前缀，避免不同表同名索引（如 idx_account_id）在 PG 同 schema 下冲突
        sb.append("INDEX IF NOT EXISTS \"").append(meta.getName()).append('_').append(index.getName())
                .append("\" ON \"").append(meta.getName()).append("\" (");
        List<String> cols = index.getColumns();
        for (int i = 0; i < cols.size(); i++) {
            sb.append('"').append(cols.get(i)).append('"');
            if (i < cols.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(")");
        return sb.toString();
    }

    private void fixTable() throws SQLException {
        List<Map<String, Object>> exist = db.query(
                "SELECT 1 AS ok FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = ?",
                ps -> ps.setString(1, meta.getName()));
        if (exist.isEmpty()) {
            db.update(buildCreateTableSQL());
        } else {
            // 补齐缺失列（PG 支持 ADD COLUMN IF NOT EXISTS）
            for (ColumnMeta col : meta.getColumns()) {
                if (col.isPk()) {
                    continue;
                }
                db.update("ALTER TABLE \"" + meta.getName() + "\" ADD COLUMN IF NOT EXISTS " + columnDDL(col));
            }
        }
        for (IndexMeta index : meta.getIndexes().values()) {
            db.update(buildCreateIndexSQL(index));
        }
    }

    // --------------------------------------------------------------- 绑定

    /** 用实体字段反射值绑定占位符（用于 INSERT/UPDATE）。镜像 JDBCRepository.prepareStatement。 */
    protected void bindField(PreparedStatement ps, int index, ColumnMeta col, T entity) throws SQLException {
        try {
            switch (col.getType()) {
                case INT:
                    ps.setInt(index, col.getField().getInt(entity));
                    break;
                case BIGINT:
                    ps.setLong(index, col.getField().getLong(entity));
                    break;
                case SMALLINT:
                    ps.setShort(index, col.getField().getShort(entity));
                    break;
                case TINYINT:
                    if (col.getField().getType() == Boolean.class || col.getField().getType() == boolean.class) {
                        ps.setShort(index, col.getField().getBoolean(entity) ? (short) 1 : 0);
                    } else {
                        ps.setShort(index, col.getField().getByte(entity));
                    }
                    break;
                case DOUBLE:
                    ps.setDouble(index, col.getField().getDouble(entity));
                    break;
                case CHAR:
                case VARCHAR:
                case TEXT:
                    Object sv = col.getField().get(entity);
                    ps.setString(index, sv == null ? (col.isNotNull() ? "" : null) : sv.toString());
                    break;
                case DATETIME:
                    Object dv = col.getField().get(entity);
                    ps.setTimestamp(index, dv == null ? null : new java.sql.Timestamp(((Date) dv).getTime()));
                    break;
                case BLOB:
                    ps.setBytes(index, (byte[]) col.getField().get(entity));
                    break;
                default:
                    ps.setString(index, GsonUtil.beanToJson(col.getField().get(entity)));
            }
        } catch (IllegalAccessException e) {
            throw new SQLException("bind field failed: " + col.getField().getName(), e);
        }
    }

    /** 用调用方提供的原始值绑定占位符（用于 WHERE/主键）。镜像 JDBCRepository.bindValue。 */
    protected void bindValue(PreparedStatement ps, int index, ColumnMeta col, Object value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
            return;
        }
        switch (col.getType()) {
            case INT:
                ps.setInt(index, ((Number) value).intValue());
                break;
            case BIGINT:
                ps.setLong(index, ((Number) value).longValue());
                break;
            case SMALLINT:
            case TINYINT:
                if (value instanceof Boolean b) {
                    ps.setShort(index, b ? (short) 1 : 0);
                } else {
                    ps.setShort(index, ((Number) value).shortValue());
                }
                break;
            case DOUBLE:
                ps.setDouble(index, ((Number) value).doubleValue());
                break;
            case CHAR:
            case VARCHAR:
            case TEXT:
                ps.setString(index, value.toString());
                break;
            case DATETIME:
                ps.setTimestamp(index, new java.sql.Timestamp(((Date) value).getTime()));
                break;
            case BLOB:
                ps.setBytes(index, (byte[]) value);
                break;
            default:
                ps.setString(index, value instanceof String s ? s : GsonUtil.beanToJson(value));
        }
    }

    // ----------------------------------------------------------------- SQL

    private String buildInsertSQL() {
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        List<ColumnMeta> columns = meta.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            cols.append('"').append(columns.get(i).getName()).append('"');
            vals.append('?');
            if (i < columns.size() - 1) {
                cols.append(", ");
                vals.append(", ");
            }
        }
        return "INSERT INTO \"" + meta.getName() + "\" (" + cols + ") VALUES (" + vals + ")";
    }

    private String buildUpdateSQL() {
        StringBuilder sb = new StringBuilder("UPDATE \"").append(meta.getName()).append("\" SET ");
        List<ColumnMeta> setCols = new ArrayList<>();
        for (ColumnMeta col : meta.getColumns()) {
            if (!col.isReadOnly() && !col.isPk()) {
                setCols.add(col);
            }
        }
        for (int i = 0; i < setCols.size(); i++) {
            sb.append('"').append(setCols.get(i).getName()).append("\" = ?");
            if (i < setCols.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(" WHERE \"").append(meta.getPk().getName()).append("\" = ?");
        return sb.toString();
    }

    private String buildSelectByPkSQL() {
        return selectColumns() + " FROM \"" + meta.getName() + "\" WHERE \"" + meta.getPk().getName() + "\" = ?";
    }

    private String selectColumns() {
        StringBuilder sb = new StringBuilder("SELECT ");
        List<ColumnMeta> columns = meta.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            sb.append('"').append(columns.get(i).getName()).append('"');
            if (i < columns.size() - 1) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    /** 选择覆盖最多查询列的索引来排列 WHERE 列（镜像 JDBCRepository.buildWhereSQL 思路）。 */
    private String buildWhereSQL(Map<String, Object> options, List<ColumnMeta> outCols, List<Object> outValues) {
        int counter = 0;
        IndexMeta used = null;
        for (IndexMeta i : meta.getIndexes().values()) {
            int c = 0;
            for (String fieldName : options.keySet()) {
                ColumnMeta col = meta.getColumnMetaByFieldName(fieldName);
                if (col == null) {
                    throw new RuntimeException("field[" + fieldName + "] not found in meta[" + meta.getName() + "]");
                }
                if (i.getColumns().contains(col.getName())) {
                    c++;
                }
            }
            if (c > counter) {
                counter = c;
                used = i;
            }
        }
        Iterator<String> it;
        if (used == null) {
            it = options.keySet().iterator();
        } else {
            List<String> list = new ArrayList<>();
            Set<String> clone = new java.util.HashSet<>(options.keySet());
            for (String colName : used.getColumns()) {
                // index columns 用列名；options 用字段名。本工程字段名==列名，直接匹配
                if (options.containsKey(colName)) {
                    list.add(colName);
                    clone.remove(colName);
                }
            }
            list.addAll(clone);
            it = list.iterator();
        }
        StringBuilder sb = new StringBuilder();
        if (it.hasNext()) {
            sb.append(" WHERE ");
        }
        while (it.hasNext()) {
            String fieldName = it.next();
            ColumnMeta col = meta.getColumnMetaByFieldName(fieldName);
            sb.append('"').append(col.getName()).append("\" = ?");
            outCols.add(col);
            outValues.add(options.get(fieldName));
            if (it.hasNext()) {
                sb.append(" AND ");
            }
        }
        return sb.toString();
    }

    private List<T> parseRows(List<Map<String, Object>> rows) throws SQLException {
        List<T> list = new ArrayList<>();
        try {
            for (Map<String, Object> map : rows) {
                @SuppressWarnings("unchecked")
                T entity = (T) meta.getClazz().getDeclaredConstructor().newInstance();
                for (ColumnMeta col : meta.getColumns()) {
                    if (map.containsKey(col.getName())) {
                        col.cast(entity, map.get(col.getName()));
                    }
                }
                list.add(entity);
            }
        } catch (Exception e) {
            throw new SQLException("build entity from result set failed", e);
        }
        return list;
    }

    // ----------------------------------------------------------------- CRUD

    @Override
    public void add(T entity) {
        String sql = buildInsertSQL();
        try {
            db.update(sql, ps -> {
                int idx = 1;
                for (ColumnMeta col : meta.getColumns()) {
                    bindField(ps, idx++, col, entity);
                }
            });
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public void add(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        for (T entity : entities) {
            add(entity);
        }
    }

    @Override
    public void remove(T entity) {
        String sql = "DELETE FROM \"" + meta.getName() + "\" WHERE \"" + meta.getPk().getName() + "\" = ?";
        try {
            db.update(sql, ps -> bindField(ps, 1, meta.getPk(), entity));
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public void remove(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        for (T entity : entities) {
            remove(entity);
        }
    }

    @Override
    public T get(Object id) {
        try {
            List<T> list = parseRows(db.query(buildSelectByPkSQL(), ps -> bindValue(ps, 1, meta.getPk(), id)));
            return list.isEmpty() ? null : list.get(0);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public List<T> listAll() {
        try {
            return parseRows(db.query(selectColumns() + " FROM \"" + meta.getName() + "\""));
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public List<T> list(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return listAll();
        }
        List<ColumnMeta> cols = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        String sql = selectColumns() + " FROM \"" + meta.getName() + "\"" + buildWhereSQL(options, cols, values);
        try {
            return parseRows(db.query(sql, ps -> {
                for (int i = 0; i < cols.size(); i++) {
                    bindValue(ps, i + 1, cols.get(i), values.get(i));
                }
            }));
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public T get(String field, Object value) {
        List<T> list = list(field, value);
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    @Override
    public List<T> list(String field, Object value) {
        Map<String, Object> options = new HashMap<>();
        options.put(field, value);
        return list(options);
    }

    @Override
    public void save(T entity) {
        forceSave(entity);
    }

    @Override
    public void save(List<T> entities) {
        forceSave(entities);
    }

    @Override
    public void forceSave(T entity) {
        String sql = buildUpdateSQL();
        try {
            db.update(sql, ps -> {
                int idx = 1;
                for (ColumnMeta col : meta.getColumns()) {
                    if (!col.isReadOnly() && !col.isPk()) {
                        bindField(ps, idx++, col, entity);
                    }
                }
                bindField(ps, idx, meta.getPk(), entity);
            });
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public void forceSave(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        for (T entity : entities) {
            forceSave(entity);
        }
    }

    @Override
    public void truncateAll() {
        try {
            db.update("TRUNCATE TABLE \"" + meta.getName() + "\"");
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
