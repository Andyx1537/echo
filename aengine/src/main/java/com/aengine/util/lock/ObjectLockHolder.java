package com.aengine.util.lock;

import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 对象锁的持有者
 *
 */
public class ObjectLockHolder {

    private final ConcurrentMap<Class<?>, Holder> holders = new ConcurrentHashMap<>();

    private class Holder {
        private final WeakHashMap<Object, ObjectLock> locks = new WeakHashMap<Object, ObjectLock>();

        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        /**
         * 获取对象锁
         *
         * @param object
         * @return
         */
        public ObjectLock getLock(Object object) {
            Lock lock = this.lock.readLock();
            try {
                lock.lock();
                ObjectLock result = locks.get(object);
                if (result != null) {
                    return result;
                }
            } finally {
                lock.unlock();
            }
            return createLock(object);
        }

        /**
         * 创建对象锁
         *
         * @param object
         * @return
         */
        private ObjectLock createLock(Object object) {
            Lock lock = this.lock.writeLock();
            try {
                lock.lock();
                ObjectLock result = locks.get(object);
                if (result != null) {
                    return result;
                }
                result = new ObjectLock(object);
                locks.put(object, result);
                return result;
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 获取指定对象实例的对象锁
     *
     * @param object        要获取锁的对象实例
     * @return
     */
    public ObjectLock getLock(Object object) {
        Holder holder = getHolder(object.getClass());
        ObjectLock lock = holder.getLock(object);
        return lock;
    }

    /**
     * 获取某类实例的锁持有者
     *
     * @param clz       指定类型
     * @return
     */
    private Holder getHolder(Class<?> clz) {
        Holder holder = holders.get(clz);
        if (holder != null) {
            return holder;
        }
        holders.putIfAbsent(clz, new Holder());
        return holders.get(clz);
    }
}
