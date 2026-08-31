package com.aengine.util.lock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于引用计数锁对象的管理类
 *
 */
public class ReferenceCountedLockManager<K> {
    /**
     * 锁的缓存
     */
    private final ConcurrentMap<K, ReferenceCountedLock<K>> locks = new ConcurrentHashMap<>();

    /**
     * 获得一个锁
     *
     * @param id        唯一标识
     * @return      所对象
     */
    private ReferenceCountedLock<K> getLock(K id) {
        ReferenceCountedLock<K> lock = locks.get(id);
        if (lock != null)
            return lock;
        lock = new ReferenceCountedLock<>(id, this);
        ReferenceCountedLock<K> old = locks.putIfAbsent(id, lock);
        if (old != null)
            return old;
        return lock;
    }

    /**
     * 加锁
     *
     * @param id        唯一标识
     */
    public void lock(K id) {
        for (;;) {
            ReferenceCountedLock<K> lock = getLock(id);
            if (lock.lock())
                break;
        }
    }

    /**
     * 解锁
     *
     * @param id        唯一标识
     */
    public void unlock(K id) {
        ReferenceCountedLock<K> lock = getLock(id);
        lock.unlock();
    }

    /**
     * 移除锁
     *
     * @param id        唯一标识
     */
    void removeLock(K id) {
        locks.remove(id);
    }
}
