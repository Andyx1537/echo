package com.aengine.persistence.redis;


import com.aengine.persistence.AbstractEntity;
import com.aengine.persistence.CacheIndex;
import com.aengine.persistence.CacheMeta;
import com.aengine.persistence.CachedRepository;
import com.aengine.persistence.annotation.CRepository;
import com.aengine.util.lock.ReferenceCountedLockManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CachedRedisRepository<T extends AbstractEntity> extends RedisRepository<T> {

    private final CachedRepository<T> cachedRepository;

    private final ReferenceCountedLockManager<String> lockManager = new ReferenceCountedLockManager<>();

    protected CachedRedisRepository() {
        super(true);
        CRepository repository = getAnnotation();
        cachedRepository = new CachedRepository<>(meta, repository.timeToIdle(), repository.timeToLive(), lockManager);
    }

    @Override
    public void add(T entity) {
        super.add(entity);
        cachedRepository.addCache(entity);
        cachedRepository.markLastChangeableField(entity, null, true);
    }

    @Override
    public void add(List<T> entities) {
        if (meta.getPk().isAuto())
            throw new RuntimeException("can not add list of entities to CachedJDBCRepository, because primary key not found");
        for (T entity : entities) {
            super.add(entity);
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
        for (T entity : entities) {
            super.remove(entity);
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
        if (entity != null)
            cachedRepository.markLastChangeableField(entity, null, false);
        return entity;
    }

    @Override
    public List<T> listAll() {
        throw new RuntimeException("不支持该方法调用，会导致所有缓存失效！");
    }

    @Override
    public List<T> list(Map<String, Object> options) {
        if (options == null || options.size() == 0)
            return listAll();
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
                    if (entity != null)
                        result.add(entity);
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
                        if (entity != null)
                            result.add(entity);
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
        return (list == null || list.size() == 0) ? null : list.get(0);
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
            super.save(entity);
            updateCacheIndex(entity);
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
}
