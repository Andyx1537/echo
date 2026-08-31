package com.aengine.util.lock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.Lock;

/**
 * 锁排序工具
 *
 */
public class LockUtil {
    /**
     * 锁持有者
     **/
    private static ObjectLockHolder holder = new ObjectLockHolder();

    /**
     * 获取多个对象的同步锁
     *
     * @param objects 要获得锁的对象或实体实例数组
     * @return 可同时锁定参数对象的锁对象
     * @throws IllegalArgumentException 对象数量为0时抛出
     */
    public static ChainLock getLock(Object... objects) {
        List<? extends Lock> locks = loadLocks(objects);
        return new ChainLock(locks);
    }

    /**
     * 获取锁定顺序正确的锁列表
     *
     * @param objects 要获得锁的对象或实体实例数组
     * @return
     */
    private static List<? extends Lock> loadLocks(Object... objects) {
        // 获取锁并排序
        List<ObjectLock> locks = new ArrayList<>();
        for (Object obj : objects) {
            ObjectLock lock = holder.getLock(obj);
            if (lock != null && !locks.contains(lock)) {
                locks.add(lock);
            }
        }
        Collections.sort(locks);
        return locks;
    }
}
