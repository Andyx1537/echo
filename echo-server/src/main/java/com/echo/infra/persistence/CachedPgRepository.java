package com.echo.infra.persistence;

import com.aengine.persistence.AbstractEntity;
import com.aengine.persistence.CacheIndex;
import com.aengine.persistence.CacheMeta;
import com.aengine.persistence.CachedRepository;
import com.aengine.persistence.ColumnMeta;
import com.aengine.persistence.annotation.CRepository;
import com.aengine.util.lock.ReferenceCountedLockManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 带缓存的 PostgreSQL 仓储：镜像 Aengine MySQL 版 {@code CachedJDBCRepository}。
 *
 * <p>内部组合方言无关的 {@link CachedRepository}（纯元数据 + 反射 + 内存缓存，零 JDBC），
 * 在 add/remove/get/list/save 各方法里按与 {@code CachedJDBCRepository} 完全一致的顺序
 * 套用 addCache/removeCache/getFromCache/setToCache/markLastChangeableField/索引缓存逻辑，
 * 底层落库委派给父类 {@link PgRepository} 的 PostgreSQL 实现。</p>
 */
public abstract class CachedPgRepository<T extends AbstractEntity> extends PgRepository<T> {

    private final CachedRepository<T> cachedRepository;

    private final ReferenceCountedLockManager<String> lockManager = new ReferenceCountedLockManager<>();

    protected CachedPgRepository() {
        super();
        CRepository repository = getAnnotation();
        this.cachedRepository = new CachedRepository<>(meta, repository.timeToIdle(), repository.timeToLive(), lockManager);
    }

    @Override
    public void add(T entity) {
        super.add(entity);
        cachedRepository.addCache(entity);
        cachedRepository.markLastChangeableField(entity, null, true);
    }

    @Override
    public void add(List<T> entities) {
        super.add(entities);
        for (T entity : entities) {
            cachedRepository.addCache(entity);
            cachedRepository.markLastChangeableField(entity, null, true);
        }
    }

    @Override
    public void remove(T entity) {
        super.remove(entity);
        cachedRepository.removeCache(entity);
        cachedRepository.removeLastChangeableField(entity);
    }

    @Override
    public void remove(List<T> entities) {
        super.remove(entities);
        for (T entity : entities) {
            cachedRepository.removeCache(entity);
            cachedRepository.removeLastChangeableField(entity);
        }
    }

    @Override
    public T get(Object id) {
        T entity = cachedRepository.getFromCache(id.toString());
        if (entity != null) {
            cachedRepository.markLastChangeableField(entity, null, false);
            return entity;
        }
        entity = super.get(id);
        T old = cachedRepository.setToCache(id.toString(), entity);
        if (old != null) {
            cachedRepository.markLastChangeableField(old, null, false);
            return old;
        }
        if (entity != null) {
            cachedRepository.markLastChangeableField(entity, null, false);
        }
        return entity;
    }

    @Override
    public List<T> listAll() {
        throw new RuntimeException("不支持该方法调用，会导致所有缓存失效！");
    }

    @Override
    public List<T> list(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return listAll();
        }
        CacheMeta used = null;
        for (CacheMeta cacheMeta : meta.getCache()) {
            if (cacheMeta.match(options)) {
                used = cacheMeta;
                break;
            }
        }
        if (used == null) {
            StringBuilder sb = new StringBuilder();
            for (String field : options.keySet()) {
                sb.append(field).append(" ");
            }
            throw new RuntimeException(meta.getClazz().getName() + ", 索引未找到: [" + sb + "]");
        }

        String key = cachedRepository.genKeyOfCache(used, options);
        lockManager.lock(key);
        try {
            CacheIndex cacheIndex = cachedRepository.getCacheIndex(key, used);
            if (cacheIndex == null) {
                cacheIndex = new CacheIndex(CacheIndex.CacheStatus.COMPLETE);
                List<T> query = super.list(options);
                List<T> result = new ArrayList<>();
                for (T entity : query) {
                    Object pk = meta.getPk().getFieldValue(entity);
                    T old = cachedRepository.setToCache(pk.toString(), entity);
                    entity = old == null ? entity : old;
                    result.add(entity);
                    cachedRepository.markLastChangeableField(entity, null, false);
                    cacheIndex.add(pk);
                }
                cachedRepository.setCacheIndex(key, used, cacheIndex);
                return result;
            } else if (cacheIndex.isComplete()) {
                Map<Object, CacheIndex.IndexStatus> identities = cacheIndex.getIdentities();
                List<T> result = new ArrayList<>();
                for (Object id : identities.keySet()) {
                    T entity = get(id);
                    if (entity != null) {
                        result.add(entity);
                    }
                }
                return result;
            } else {
                List<T> query = super.list(options);
                Map<Object, T> temp = new HashMap<>();
                for (T entity : query) {
                    Object pk = meta.getPk().getFieldValue(entity);
                    temp.put(pk, entity);
                }
                cachedRepository.updateCacheIndex(key, used, temp.keySet());
                Map<Object, CacheIndex.IndexStatus> identities = cacheIndex.getIdentities();
                List<T> result = new ArrayList<>();
                for (Object identity : identities.keySet()) {
                    T entity = temp.get(identity);
                    if (entity != null) {
                        T old = cachedRepository.setToCache(identity.toString(), entity);
                        entity = old == null ? entity : old;
                        result.add(entity);
                        cachedRepository.markLastChangeableField(entity, null, false);
                        cacheIndex.add(identity);
                    } else {
                        entity = get(identity);
                        if (entity != null) {
                            result.add(entity);
                        }
                    }
                }
                return result;
            }
        } finally {
            lockManager.unlock(key);
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
        super.save(entity);
        updateCacheIndex(entity);
    }

    @Override
    public void save(List<T> entities) {
        for (T entity : entities) {
            save(entity);
        }
    }

    protected void updateCacheIndex(T entity) {
        if (cachedRepository.isNeedUpdateIndexCache()) {
            Object pk = meta.getPk().getFieldValue(entity);
            String lockKey = meta.getName() + "_" + pk.toString();
            lockManager.lock(lockKey);
            try {
                cachedRepository.updateCache(entity);
            } finally {
                lockManager.unlock(lockKey);
            }
        }
    }

    /** 供单测验证缓存命中而不回源：返回二级缓存中的实体（不触发 DB）。 */
    protected T peekCache(Object id) {
        return cachedRepository.getFromCache(id.toString());
    }
}
