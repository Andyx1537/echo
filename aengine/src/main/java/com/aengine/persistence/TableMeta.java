package com.aengine.persistence;

import com.aengine.persistence.annotation.*;
import com.aengine.persistence.db.DB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.*;

/**
 * 数据表结构
 *
 */
public class TableMeta {

    /*
	 * 与该数据表结构对应的类
     */
    private Class<? extends AbstractEntity> clazz = null;

    /*
	 * 数据表的名字
     */
    private String name;

    /*
	 * 数据表的注释
     */
    private String comment;

    /*
	 * 索引
     */
    private final Map<String, IndexMeta> indexes = new HashMap<>();

    /*
	 * 列
     */
    private final List<ColumnMeta> columns = new ArrayList<>();

    /*
	 * 类的字段名到列的映射
     */
    private final Map<String, ColumnMeta> field2column = new HashMap<>();

    /*
	 * 列名到类的字段名的映射
     */
    private final Map<String, ColumnMeta> column2field = new HashMap<>();

    /*
	 * 缓存
     */
    private final List<CacheMeta> cache = new ArrayList<>();

    /*
	 * 主键
     */
    private ColumnMeta pk = null;

    /*
	 * 分片的个数
     */
    private int cluster;

    /*
	 * 分片的列
     */
    private ColumnMeta clusterBy = null;

    private String charset;

    private boolean autoCreate;

    private HashMap<String, Map<Long, Integer>> sqlCostCache = new HashMap<>();

    private static Logger log = LoggerFactory.getLogger(TableMeta.class);

    private TableMeta() {
    }

    public Class<? extends AbstractEntity> getClazz() {
        return clazz;
    }

    public String getName() {
        return name;
    }

    public String getComment() {
        return comment;
    }

    public Map<String, IndexMeta> getIndexes() {
        return indexes;
    }

    public List<ColumnMeta> getColumns() {
        return columns;
    }

    public ColumnMeta getPk() {
        return pk;
    }

    public int getCluster() {
        return cluster;
    }

    public ColumnMeta getClusterBy() {
        return clusterBy;
    }

    public ColumnMeta getColumnMetaByFieldName(String filedName) {
        return field2column.get(filedName);
    }

    public ColumnMeta getColumnMetaByColumnName(String columnName) {
        return column2field.get(columnName);
    }

    public String getCharset() {
        return charset;
    }

    public boolean isAutoCreate() {
        return autoCreate;
    }

    public List<CacheMeta> getCache() {
        return cache;
    }


    public static TableMeta parse(Class<? extends AbstractEntity> clazz) throws MetaException {
        return parse(clazz, false);
    }
    /**
     * 将{@link AbstractEntity}的子类转换为{@link TableMeta}
     *
     * @param clazz 数据表结构对应的类
     * @return 数据表结构
     * @throws MetaException 类定义错误时抛出该异常
     */
    public static TableMeta parse(Class<? extends AbstractEntity> clazz, boolean autoCacheIndex) throws MetaException {
        //检查类注解
        Table table = clazz.getAnnotation(Table.class);
        if (table == null) {
            table = clazz.getSuperclass().getAnnotation(Table.class);
            if (table == null)
                throw new MetaException("@Table not found in class: " + clazz.getName());
        }
        if ("".equals(table.name().trim())) {
            throw new MetaException("table name can not be empty:" + clazz.getName());
        }
        TableMeta meta = new TableMeta();
        meta.clazz = clazz;
        meta.name = table.name();
        meta.comment = table.comment();
        meta.cluster = table.cluster();
        String clusterBy = table.clusterBy();
        meta.charset = table.charset().trim();
        meta.autoCreate = table.autoCreate();

        List<Field> fields = getAllFieldsWithParent(clazz);
        //列
        for (Field field : fields) {
            field.setAccessible(true);
            ColumnMeta col = ColumnMeta.parse(field);
            if (col == null) {
                continue;
            }
            // 外键索引
            Fk fk = field.getAnnotation(Fk.class);
            if (field.getAnnotation(Fk.class) != null) {
                IndexMeta index = new IndexMeta("idx_" + col.getName(), fk.type(), col.getName());
                meta.indexes.put(index.getName(), index);
            }
            meta.columns.add(col);
            meta.column2field.put(col.getName(), col);
            meta.field2column.put(col.getField().getName(), col);
			if (col.isPk()) {
				if (meta.pk == null) {
					meta.pk = col;
				} else {
					throw new MetaException("duplicate definition of primary key: " + clazz.getName());
				}
			}
            if (col.getName().equals(clusterBy)) {
                meta.clusterBy = col;
            }
        }
        Set<Set<String>> alreadyCache = new HashSet<>();
        //索引
        for (Index idx : table.index()) {
            IndexMeta index = IndexMeta.parse(idx);
            meta.indexes.put(index.getName(), index);

        }
        if (autoCacheIndex) {
            for (IndexMeta index : meta.indexes.values()) {
                Set<String> cols = new HashSet<>();
                CacheMeta cacheMeta = new CacheMeta();
                for (String cName : index.getColumns()) {
                    ColumnMeta columnMeta = meta.column2field.get(cName);
                    cacheMeta.add(columnMeta);
                    cols.add(cName);
                }
                alreadyCache.add(cols);
                meta.cache.add(cacheMeta);
            }
        }
        //缓存
        for (Cache c : table.cache()) {
            if (c.columns().length == 0) {
                continue;
            }
            //是否已经由index创建缓存
            if (autoCacheIndex) {
                boolean already = false;
                for (Set<String> cols : alreadyCache) {
                    if (cols.size() == c.columns().length) {
                        boolean flag = true;
                        for (String cName : c.columns()) {
                            if (!cols.contains(cName)) {
                                flag = false;
                                break;
                            }
                        }
                        if (flag) {
                            already = true;
                            break;
                        }
                    }
                }
                if (already)
                    continue;
            }

            CacheMeta cacheMeta = new CacheMeta();
            for (String cName : c.columns()) {
                ColumnMeta columnMeta = meta.column2field.get(cName);
                if (!columnMeta.isReadOnly() && !autoCacheIndex) {
                    throw new MetaException("cached field should be read only:" + clazz.getName() + "." + columnMeta.getField().getName());
                }
                cacheMeta.add(columnMeta);
            }
            meta.cache.add(cacheMeta);
        }

        if (meta.pk == null) {
            throw new MetaException("primary key not found: " + clazz.getName());
        }
        if (!"".equals(table.clusterBy()) && meta.clusterBy == null) {
            throw new MetaException("cluster field: " + table.clusterBy() + " not found: " + clazz.getName());
        }
        return meta;
    }

    public static TableMeta getTableFromDB(String tableName, DB db) throws Exception {
        TableMeta table = new TableMeta();
        table.name = tableName;
        String sql = "SHOW FULL FIELDS FROM `" + tableName + "`";
        for (Map<String, Object> map : db.query(sql)) {
            ColumnMeta column = ColumnMeta.parse(map);
            table.column2field.put(column.getName(), column);
        }
        sql = "SHOW INDEX FROM `" + tableName + "`";
        for (Map<String, Object> map : db.query(sql)) {
            String keyName = map.get("Key_name").toString();
            if ("PRIMARY".equalsIgnoreCase(keyName)) {
                continue;
            }
            IndexMeta index = table.getIndexes().get(keyName);
            if (index == null) {
                index = new IndexMeta();
                index.setName(keyName);
                table.getIndexes().put(keyName, index);
            }
            index.setType(Index.IndexType.NORMAL);
            if ("0".equals(map.get("Non_unique").toString())) {
                index.setType(Index.IndexType.UNIQUE);
            }
            if ("FULLTEXT".equalsIgnoreCase(map.get("Index_type").toString())) {
                index.setType(Index.IndexType.FULLTEXT);
            }
            index.getColumns().add(map.get("Column_name").toString());
        }
        return table;
    }

    /**
     * 获取父类（被{@link MappedSuperclass}注解）的所有field
     */
    private static void getFieldsOfParent(Class<?> clazz, List<Field> list) {
        Class<?> parent = clazz.getSuperclass();
        MappedSuperclass superclass = parent.getAnnotation(MappedSuperclass.class);
        if (superclass == null) {
            return;
        }
        Collections.addAll(list, parent.getDeclaredFields());
        if (parent.getSuperclass() != null) {
            getFieldsOfParent(parent, list);
        }
    }

    /**
     * 获取类的所有field，包括从父类（被{@link MappedSuperclass}注解）中继承的field
     */
    private static List<Field> getAllFieldsWithParent(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        getFieldsOfParent(clazz, fields);
        Collections.addAll(fields, clazz.getDeclaredFields());
        return fields;
    }

    /**
     * 获取该实体对象实际的存储名字
     *
     * @param entity 实体对象
     * @return 数据存储的名字
     */
    public String getRealName(AbstractEntity entity) {
        if (clusterBy == null) {
            return name;
        }
        Field field = clusterBy.getField();
        field.setAccessible(true);
        try {
            Object value = field.get(entity);
            return name + "_" + Math.abs(value.hashCode() % cluster);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> getTruncateTableName() {
        List<String> result = new ArrayList<>();
        if (clusterBy == null) {
            result.add(name);

        } else {
            for (int i = 0; i < cluster; i++) {
                result.add(name + "_" + i);
            }
//            Field field = clusterBy.getField();
//            field.setAccessible(true);
//            try {
//                Object value = field.get(entity);
//                return name + "_" + Math.abs(value.hashCode() % cluster);
//            } catch (IllegalAccessException e) {
//                throw new RuntimeException(e);
//            }
        }
        return result;

    }
    

//    public void updateSqlUse(String sql, long useTime) {
//        Map<Long, Integer> timeCost = null;
//        if (sqlCostCache.containsKey(sql)) {
//            timeCost = sqlCostCache.get(sql);
//        } else {
//            timeCost = new HashMap<>();
//            sqlCostCache.put(sql, timeCost);
//        }
//        long timeUseDiv = useTime / 100 * 100;
//        int times = 1;
//        if (timeCost.containsKey(timeUseDiv)) {
//
//            timeCost.put(timeUseDiv, times + timeCost.get(timeUseDiv));
//        } else {
//            timeCost.put(timeUseDiv, times);
//        }
//
//    }
//
//    public void printSqlCost() {
//
//        long sqlTimes = 0;
//        log.warn("taleName : " + name + " ");
//        for (Entry<String, Map<Long, Integer>> entry : sqlCostCache.entrySet()) {
//            log.warn("sql :" + entry.getKey());
//            StringBuilder sb = new StringBuilder();
//            sb.append("cost cal : ");
//            for (Entry<Long, Integer> entryCost : entry.getValue().entrySet()) {
//                sb.append(entryCost.getKey()).append("ms, times: " + entryCost.getValue()+ "/r/n");
//                sqlTimes += entryCost.getValue();
//            }
//            log.warn(sb.toString());
//
//        }
//        log.warn("total times :" + sqlTimes);
//
//    }

}
