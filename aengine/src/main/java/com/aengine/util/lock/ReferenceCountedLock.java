package com.aengine.util.lock;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于引用计数的锁
 *
 */
public class ReferenceCountedLock<K> extends AbstractReferenceCounted{
    private final Lock lock = new ReentrantLock();
    private final ReferenceCountedLockManager<K> manager;
    private final K id;

    ReferenceCountedLock(K id, ReferenceCountedLockManager<K> manager) {
        this.manager = manager;
        this.id = id;
    }

    /**
     * 加锁，阻塞
     * @return  加锁成功返回true，加锁失败返回false。当返回false时意味着需要重新获得锁
     */
    boolean lock() {
        if (!retain())
            return false;
        lock.lock();
        return true;
    }

    /**
     * 解锁
     */
    void unlock() {
        release();
        lock.unlock();
    }

    @Override
    protected void deallocate() {
        manager.removeLock(id);
    }
}
