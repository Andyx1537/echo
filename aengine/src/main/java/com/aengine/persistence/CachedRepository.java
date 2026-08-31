package com.aengine.persistence;


import com.aengine.cache.ICache;
import com.aengine.cache.SimpleCache;
import com.aengine.util.lock.ReferenceCountedLockManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 带缓存的仓储
 *
 *
 */
public class CachedRepository<T extends AbstractEntity> {

    protected final ICache<String, T> cache;

    protected final Map<CacheMeta, ICache<String, CacheIndex>> indexCache;

    private final Set<ColumnMeta> changeableCacheField = new HashSet<>();

    private final Map<T, ConcurrentMap<String, Object>> lastChangeableFieldValue = Collections.synchronizedMap(new WeakHashMap<>());

    private final ReferenceCountedLockManager<String> lockManager;

    private final TableMeta meta;

    public CachedRepository(TableMeta meta, int timeToIdle, int timeToLive, ReferenceCountedLockManager<String> lockManager) {
        this.cache = new SimpleCache<>(timeToIdle, timeToLive);
        this.indexCache = new HashMap<>();
        this.meta = meta;
        this.lockManager = lockManager;
        for (CacheMeta cacheMeta : meta.getCache()) {
            indexCache.put(cacheMeta, new SimpleCache<>(timeToIdle ,timeToLive));

            for (ColumnMeta columnMeta : cacheMeta.getColumns()) {
                if (!columnMeta.isReadOnly()) {
                    changeableCacheField.add(columnMeta);
                }
            }
        }
    }

    public String genKeyOfCache(CacheMeta cacheMeta, T entity) {
        StringBuilder sb = new StringBuilder();
        sb.append(meta.getName()).append("_");
        for (int i = 0; i < cacheMeta.getColumns().size(); i++) {
            ColumnMeta columnMeta = cacheMeta.getColumns().get(i);
            sb.append(columnMeta.getField().getName()).append("_");
            sb.append(columnMeta.getFieldValue(entity));
            if (i < cacheMeta.getColumns().size() - 1)
                sb.append("_");
        }
        return sb.toString();
    }

    public String genKeyOfCache(CacheMeta cacheMeta, Map<String, Object> options) {
        StringBuilder sb = new StringBuilder();
        sb.append(meta.getName()).append("_");
        for (int i = 0; i < cacheMeta.getColumns().size(); i++) {
            ColumnMeta columnMeta = cacheMeta.getColumns().get(i);
            sb.append(columnMeta.getField().getName()).append("_");
            sb.append(options.get(columnMeta.getField().getName()));
            if (i < cacheMeta.getColumns().size() - 1)
                sb.append("_");
        }
        return sb.toString();
    }

    public void addCache(T entity) {
        Object pk = meta.getPk().getFieldValue(entity);
        T old = cache.putIfAbsent(pk.toString(), entity);
        if (old != null)
            entity = old;
        for (CacheMeta n : meta.getCache()) {
            ICache<String, CacheIndex> cache = indexCache.get(n);
            String key = genKeyOfCache(n, entity);
            lockManager.lock(key);
            try {
                CacheIndex cacheIndex = cache.get(key);
                if (cacheIndex == null) {
                   cacheIndex = new CacheIndex(CacheIndex.CacheStatus.NOT_COMPLETE);
                   cache.put(key, cacheIndex);
                }
                cacheIndex.add(pk);
            } finally {
                lockManager.unlock(key);
            }
        }
    }

    public void removeCache(T entity) {
        Object pk = meta.getPk().getFieldValue(entity);
        cache.remove(pk.toString());
        for (CacheMeta n : meta.getCache()) {
            ICache<String, CacheIndex> cache = indexCache.get(n);
            String key = genKeyOfCache(n, entity);
            lockManager.lock(key);
            try {
                CacheIndex cacheIndex = cache.get(key);
                if (cacheIndex == null) {
                    cacheIndex = new CacheIndex(CacheIndex.CacheStatus.NOT_COMPLETE);
                    cache.put(key, cacheIndex);
                }
                cacheIndex.remove(pk);
            } finally {
                lockManager.unlock(key);
            }
        }
    }

    public void markLastChangeableField(T entity, Map<String, Object> current, boolean overwrite) {
        if (changeableCacheField.isEmpty())
            return;
        lastChangeableFieldValue.computeIfAbsent(entity, k -> new ConcurrentHashMap<>());
        Map<String, Object> map = lastChangeableFieldValue.get(entity);
        if (current == null) {
            for (ColumnMeta columnMeta : changeableCacheField) {
                if (overwrite)
                    map.put(columnMeta.getField().getName(), columnMeta.getFieldValue(entity));
                else
                    map.putIfAbsent(columnMeta.getField().getName(), columnMeta.getFieldValue(entity));
            }
        } else {
            for (ColumnMeta columnMeta : changeableCacheField) {
                if (overwrite)
                    map.put(columnMeta.getField().getName(), current.get(columnMeta.getField().getName()));
                else
                    map.putIfAbsent(columnMeta.getField().getName(), current.get(columnMeta.getField().getName()));
            }
        }
    }

    public void removeLastChangeableField(T entity) {
        if (changeableCacheField.isEmpty())
            return;
        lastChangeableFieldValue.remove(entity);
    }

    private void updateCache(CacheMeta cacheMeta, T entity, Map<String, Object> lastMap, Map<String, Object> currentMap) {
        boolean isChange = false;
        for (ColumnMeta columnMeta : cacheMeta.getColumns()) {
            if (lastMap.containsKey(columnMeta.getField().getName())) {
                isChange = true;
                break;
            }
        }
        if (!isChange)
            return;

        //补齐只读数据
        for (ColumnMeta columnMeta : cacheMeta.getColumns()) {
            if (!currentMap.containsKey(columnMeta.getField().getName())) {
                lastMap.put(columnMeta.getField().getName(), columnMeta.getFieldValue(entity));
                currentMap.put(columnMeta.getField().getName(), columnMeta.getFieldValue(entity));
            }
        }

        String key = genKeyOfCache(cacheMeta, lastMap);
        Object pk = meta.getPk().getFieldValue(entity);
        ICache<String, CacheIndex> cache = indexCache.get(cacheMeta);

        lockManager.lock(key);
        try {
            CacheIndex cacheIndex = cache.get(key);
            if (cacheIndex == null) {
                cacheIndex = new CacheIndex(CacheIndex.CacheStatus.NOT_COMPLETE);
                cache.put(key, cacheIndex);
            }
            cacheIndex.remove(pk);
        } finally {
            lockManager.unlock(key);
        }

        key = genKeyOfCache(cacheMeta, currentMap);
        lockManager.lock(key);
        try {
            CacheIndex cacheIndex = cache.get(key);
            if (cacheIndex == null) {
                cacheIndex = new CacheIndex(CacheIndex.CacheStatus.NOT_COMPLETE);
                cache.put(key, cacheIndex);
            }
            cacheIndex.add(pk);
        } finally {
            lockManager.unlock(key);
        }
    }

    private void updateCache(T entity, ConcurrentMap<String, Object> last, Map<String, Object> currentMap) {
        boolean change = false;
        for (String fieldName : last.keySet()) {
            Object old = last.get(fieldName);
            Object current = currentMap.get(fieldName);
            if (!Objects.equals(old, current)) {
                change = true;
                break;
            }
        }
        if (!change)
            return;

        for (CacheMeta cacheMeta : meta.getCache()) {
            updateCache(cacheMeta, entity, last, currentMap);
        }
    }

    public void updateCache(T entity) {
        ConcurrentMap<String, Object> last = lastChangeableFieldValue.get(entity);
        Map<String, Object> current = new HashMap<>();
        for (ColumnMeta columnMeta : changeableCacheField) {
            current.put(columnMeta.getField().getName(), columnMeta.getFieldValue(entity));
        }
        updateCache(entity, last, current);
        markLastChangeableField(entity, current, true);
    }

    public T getFromCache(String identity) {
        return cache.get(identity);
    }

    public T setToCache(String identity, T entity) {
        return cache.putIfAbsent(identity, entity);
    }

    public CacheIndex getCacheIndex(String index, CacheMeta used) {
        return indexCache.get(used).get(index);
    }

    public void setCacheIndex(String index, CacheMeta used, CacheIndex cacheIndex) {
        indexCache.get(used).put(index, cacheIndex);
    }

    public void updateCacheIndex(String index, CacheMeta used, Collection<Object> identities) {
        CacheIndex cacheIndex = indexCache.get(used).get(index);
        cacheIndex.complete(identities);
    }

    public boolean isNeedUpdateIndexCache() {
        return !changeableCacheField.isEmpty();
    }

}
