package com.aengine.persistence;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 缓存索引
 * 当前缓存的唯一标识列表
 *
 */
public class CacheIndex {

    private CacheStatus status;

    private final Map<Object, IndexStatus> identities = new HashMap<>();

    public CacheIndex(CacheStatus status) {
        this.status = status;
    }

    public void add(Object identity) {
        if (status == CacheStatus.COMPLETE)
            identities.put(identity, IndexStatus.NORMAL);
        else
            identities.put(identity, IndexStatus.ADDED);
    }

    public void remove(Object identity) {
        if (status == CacheStatus.COMPLETE)
            identities.remove(identity);
        else
            identities.put(identity, IndexStatus.REMOVED);
    }

    public Map<Object, IndexStatus> getIdentities() {
        return identities;
    }

    public void complete(Collection<Object> list) {
        status = CacheStatus.COMPLETE;
        for (Object identity : list) {
            if (!identities.containsKey(identity)) {
                identities.put(identity, IndexStatus.NORMAL);
            }
        }
        identities.values().removeIf(n -> n == IndexStatus.REMOVED);
        identities.replaceAll((k, v) -> v = IndexStatus.NORMAL);
    }

    public boolean isComplete() {
        return status == CacheStatus.COMPLETE;
    }

    public enum CacheStatus {
        NOT_COMPLETE, COMPLETE
    }

    public enum IndexStatus {
        NORMAL, REMOVED, ADDED
    }
}
