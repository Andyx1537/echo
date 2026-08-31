package com.aengine.persistence.redis;

import com.aengine.persistence.*;
import com.aengine.persistence.annotation.Index;
import com.aengine.persistence.db.DBManager;
import com.aengine.util.GsonUtil;
import com.aengine.util.lock.distributedLock.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.ParameterizedType;
import java.util.*;

/**
 */
public class RedisRepository<T extends AbstractEntity> implements IRepository<T> {

    private static final Logger log = LoggerFactory.getLogger(RedisRepository.class);

    protected final Redis redis;

    protected final TableMeta meta;

    private final DistributedLock lock;

    private static final String FIX_TOKEN = ":";

    @SuppressWarnings("unchecked")
    protected Class<T> getClassOfEntity() {
        return (Class<T>) ((ParameterizedType) (getClass().getGenericSuperclass())).getActualTypeArguments()[0];
    }

    public RedisRepository() {
        this(false);
    }

    public RedisRepository(boolean autoCacheIndex) {
//        CRepository repository = getAnnotation();//TODO 多redis 配置时根据 source 获取对应的redis 来源
        this.redis = DBManager.getInstance().getRedisCache();
        this.meta = TableMeta.parse(getClassOfEntity(), autoCacheIndex);
        this.lock = new DistributedLock(redis);
    }

    private String genKeyOfIndex(String indexName) {
        return meta.getName() + FIX_TOKEN + indexName;
    }

    private String genKeyOfAutoId() {
        // 暂时没想到好的命名方式
        return "TablemetaAutoId" + FIX_TOKEN + meta.getName();
//        return meta.getName() + "autoid";
    }

    protected Object getPk(T entity) {
        return meta.getPk().getFieldValue(entity);
    }

    protected IndexMeta getIndexByOptions(Map<String, Object> options) {
        IndexMeta index = null;
        index:
        for (IndexMeta indexMeta : meta.getIndexes().values()) {
            if (indexMeta.getColumns().size() > 0 && indexMeta.getColumns().size() == options.size()) {
                for (String columnName : indexMeta.getColumns()) {
                    ColumnMeta columnMeta = meta.getColumnMetaByColumnName(columnName);
                    if (!options.containsKey(columnMeta.getField().getName())) {
                        continue index;
                    }
                }
                index = indexMeta;
                break;
            }
        }
        return index;
    }

    protected String getIndexValue(IndexMeta index, Map<String, Object> options) {
        StringBuilder sb = index.getType() == Index.IndexType.UNIQUE ? new StringBuilder()
                : new StringBuilder(genKeyOfIndex(index.getName())).append(FIX_TOKEN);
        for (int i = 0; i < index.getColumns().size(); i++) {
            ColumnMeta columnMeta = meta.getColumnMetaByColumnName(index.getColumns().get(i));
            sb.append(options.get(columnMeta.getField().getName()));
            if (i < index.getColumns().size() - 1) {
                sb.append(FIX_TOKEN);
            }
        }
        return sb.toString();
    }

    protected String getIndexValue(IndexMeta index, T entity) {
        StringBuilder sb = index.getType() == Index.IndexType.UNIQUE ? new StringBuilder()
                : new StringBuilder(genKeyOfIndex(index.getName())).append(FIX_TOKEN);
        for (int i = 0; i < index.getColumns().size(); i++) {
            String columnName = index.getColumns().get(i);
            sb.append(meta.getColumnMetaByColumnName(columnName).getFieldValue(entity));
            if (i < index.getColumns().size() - 1) {
                sb.append(FIX_TOKEN);
            }
        }
        return sb.toString();
    }

    protected String addIndex(IndexMeta indexMeta, T entity) {
        String pk = getPk(entity).toString();
        String keyValue = getIndexValue(indexMeta, entity);
        if (indexMeta.getType() == Index.IndexType.UNIQUE) {
            String key = genKeyOfIndex(indexMeta.getName());
            Redis redisByIndex = DBManager.getInstance().getRedisByIndex(key);
            long rs = redisByIndex.hSetNX(key, keyValue, pk);
            if (rs == 0) {
                throw new RuntimeException("duplicate key(" + keyValue + ") on " + key);
            }
            if (log.isDebugEnabled()) {
                log.debug("add unique index(" + keyValue + ") into " + key);
            }
        } else if (indexMeta.getType() == Index.IndexType.NORMAL) {
            Redis redisByIndex = DBManager.getInstance().getRedisByIndex(keyValue);
            redisByIndex.sAdd(keyValue, pk);
            if (log.isDebugEnabled()) {
                log.debug("add normal index(" + pk + ") into " + keyValue);
            }
        }
        return keyValue;
    }

    protected String removeIndex(IndexMeta indexMeta, T entity) {
        String keyValue = getIndexValue(indexMeta, entity);
        if (indexMeta.getType() == Index.IndexType.UNIQUE) {

            String key = genKeyOfIndex(indexMeta.getName());
            Redis redisByIndex = DBManager.getInstance().getRedisByIndex(key);
            redisByIndex.hDel(key, keyValue);
            if (log.isDebugEnabled()) {
                log.debug("remove unique index(" + keyValue + ") from " + key);
            }
        } else if (indexMeta.getType() == Index.IndexType.NORMAL) {
            String pk = getPk(entity).toString();
            // 必须与 addIndex 用相同的路由键(keyValue)选分片，否则会落到不同 redis 实例导致删不掉索引
            Redis redisByIndex = DBManager.getInstance().getRedisByIndex(keyValue);
            redisByIndex.sRem(keyValue, pk);
            if (log.isDebugEnabled()) {
                log.debug("remove normal index(" + pk + ") from " + keyValue);
            }
        }
        return keyValue;
    }

    @Override
    public void add(T entity) {
        // 自增id

        if (meta.getPk().isAuto()) {
            String key = genKeyOfAutoId();//都从这里取.然后存在不同地方
            Redis redisByIndex = DBManager.getInstance().getRedisCache();
            long id = redisByIndex.incr(key);
            meta.getPk().cast(entity, id);
        }

        // 加入实体
        String pk = getPk(entity).toString();
        Redis redisByIndex = DBManager.getInstance().getRedisByIndex(pk);
        String json = GsonUtil.beanToJson(entity);
        long rs = redisByIndex.hSetNX(meta.getName(), pk, json);
        if (rs == 0) {
            throw new RuntimeException("duplicate primary key:" + pk + " of " + meta.getName());
        }

        // 加入索引
        meta.getIndexes().forEach((k, v) -> addIndex(v, entity));
        if (log.isDebugEnabled()) {
            log.debug("add " + meta.getName() + ", " + pk + " = " + json);
        }
    }

    @Override
    public void add(List<T> entities) {
        entities.forEach(this::add);
    }

    @Override
    public void remove(T entity) {
        String pk = getPk(entity).toString();
        Redis redisByIndex = DBManager.getInstance().getRedisByIndex(pk);
        // 移除索引
        meta.getIndexes().forEach((k, v) -> removeIndex(v, entity));
        // 移除实体数据
        redisByIndex.hDel(meta.getName(), pk);
        if (log.isDebugEnabled()) {
            log.debug("remove " + meta.getName() + ", " + pk);
        }
    }

    @Override
    public void remove(List<T> entities) {
        entities.forEach(this::remove);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get(Object id) {
        Redis redisByIndex = DBManager.getInstance().getRedisByIndex(id.toString());
        String json = redisByIndex.hGet(meta.getName(), id.toString());
        return GsonUtil.jsonToBean(json, (Class<T>) meta.getClazz());

    }

    @Override
    @SuppressWarnings("unchecked")
    public List<T> listAll() {
        List<String> values = new ArrayList<>();
        Set<String> resultSet = new HashSet<>();
        for (Redis redisTem : DBManager.getInstance().allRedisInstance()) {
//            values.addAll(redis.hValues(meta.getName()));
            resultSet.addAll(redis.hValues(meta.getName()));
        }

        if (values == null) {
            return null;
        }
        List<T> result = new ArrayList<>(values.size());
        for (String json : resultSet) {
            result.add(GsonUtil.jsonToBean(json, (Class<T>) meta.getClazz()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<T> list(Map<String, Object> options) {
        if (options.size() == 0) {
            return listAll();
        }
        IndexMeta index = getIndexByOptions(options);
        if (index == null) {
            throw new RuntimeException("options not match index of class:" + meta.getClazz().getName());
        }

        List<T> result = new ArrayList<>();
        if (index.getType() == Index.IndexType.UNIQUE) {
            String key = genKeyOfIndex(index.getName());
            Redis redisByIndex = DBManager.getInstance().getRedisByIndex(key);
            String keyValue = getIndexValue(index, options);
            String pk = redisByIndex.hGet(key, keyValue);
            if (pk != null) {
                T entity = get(pk);
                if (entity != null) {
                    result.add(entity);
                }
            }
        } else if (index.getType() == Index.IndexType.NORMAL) {
            String keyValue = getIndexValue(index, options);
            Redis redisByIndex = DBManager.getInstance().getRedisByIndex(keyValue);
            Set<String> pks = redisByIndex.sMembers(keyValue);
            if (pks.size() > 0) {
                String[] str = new String[pks.size()];
                pks.toArray(str);
//                List<String> resultList =new ArrayList<>();
                Set<String> resultSet = new HashSet<>();
                for (Redis redisTemp : DBManager.getInstance().allRedisInstance()) {
                    List<String> list = redisTemp.hmGet(meta.getName(), str);
                    for (String json : list) {
                        resultSet.add(json);
                    }
                }//去重
                for (String json : resultSet) {
                    if (json == null) {
                        continue;
                    }
                    result.add(GsonUtil.jsonToBean(json, (Class<T>) meta.getClazz()));
                }
            }
        }
        return result;
    }

    public long count(Map<String, Object> options) {
        if (options.size() == 0)
            throw new RuntimeException("empty query options");
        IndexMeta index = getIndexByOptions(options);
        if (index == null) {
            throw new RuntimeException("options not match index of class:" + meta.getClazz().getName());
        }

        if (index.getType() == Index.IndexType.UNIQUE) {
            String key = genKeyOfIndex(index.getName());
            Redis redisByIndex = DBManager.getInstance().getRedisByIndex(key);
            return redisByIndex.hLen(key);
        } else if (index.getType() == Index.IndexType.NORMAL) {
            String keyValue = getIndexValue(index, options);
            Redis redisByIndex = DBManager.getInstance().getRedisByIndex(keyValue);
            return redisByIndex.sCard(keyValue);
        } else {
            return -1L;
        }
    }

    public long count(String field, Object value) {
        Map<String, Object> options = new HashMap<>();
        options.put(field, value);
        return count(options);
    }


    @Override
    public T get(String field, Object value) {
        List<T> list = list(field, value);
        if (list.isEmpty()) {
            return null;
        }
        return list.get(0);
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
        for (T entity : entities)
            forceSave(entity);
    }


    @Override
    public void forceSave(List<T> entities) {
        for (T entity : entities)
            forceSave(entity);
    }

    @Override
    public void forceSave(T entity) {
        String pk = getPk(entity).toString();
        String lockKey = "save" + FIX_TOKEN + "locktoken" + FIX_TOKEN + meta.getName() + FIX_TOKEN + pk;
        lock.lock(lockKey);

        try {
            @SuppressWarnings("unchecked")
            Redis redisByIndex = DBManager.getInstance().getRedisByIndex(pk);
            T oldEntity = GsonUtil.jsonToBean(redisByIndex.hGet(meta.getName(), pk), (Class<T>) meta.getClazz());
            if (oldEntity == null) {
                throw new RuntimeException(meta.getName() + "(" + pk + ") not found in redis");
            }

            String json = GsonUtil.beanToJson(entity);
            redisByIndex.hSet(meta.getName(), pk, json);
            if (log.isDebugEnabled()) {
                log.debug("save " + meta.getName() + ", " + pk + " = " + json);
            }

            // 判断是否发生修改
            meta.getIndexes().forEach((k, v) -> {
                for (String columnName : v.getColumns()) {
                    ColumnMeta columnMeta = meta.getColumnMetaByColumnName(columnName);
                    Object old = columnMeta.getFieldValue(oldEntity);
                    Object cur = columnMeta.getFieldValue(entity);
                    if (!old.equals(cur)) {
                        addIndex(v, entity);
                        removeIndex(v, oldEntity);
                        break;
                    }
                }
            });
        } finally {
            lock.unlock(lockKey);
        }
    }

    @Override
    public void truncateAll() {
        // 暂时不支持
    }

}
