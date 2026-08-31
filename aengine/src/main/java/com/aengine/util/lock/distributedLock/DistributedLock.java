/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.aengine.util.lock.distributedLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * 基于 Redis 的分布式锁。
 *
 * <p>本类<b>无状态</b>（仅持有 {@link RedisLockSupport}），可被多 key、多线程安全共享。</p>
 *
 * <p>使用约定：{@code if (lock(key)) { try { ... } finally { unlock(key); } }}。
 * 业务处理时长远小于锁 TTL（{@link #LOCK_TIME} 秒），因此在 unlock 时锁必然仍为本持有者所有，
 * 直接按 key 删除即可——这正是“持锁者主动释放”的语义。</p>
 *
 * <p>历史实现使用过一个实例级 {@code boolean lock} 标志来决定是否删除锁，这在被多 key / 多线程
 * 共享同一实例（如 EventBus 全局单实例）时是错误的：该标志一旦置 true 便永不复位，会导致
 * unlock 误删本不属于当前调用、甚至未曾加锁的 key。故已彻底移除该标志。</p>
 */
public class DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(DistributedLock.class);

    private final RedisLockSupport redis;

    private static final String LOCK = "lock";
    private static final String SEPERATE_TOKEN = "_";

    private static final int LOCK_TIME = 10;

    public DistributedLock(RedisLockSupport redis) {
        this.redis = redis;
    }

    /**
     * 加锁 使用方式为： if (lock(key)) { try{ executeMethod(); }finally{ unlock(key); } }
     *
     * @return 成功 or 失败
     */
    public boolean lock(String identyKey) {

        long startTime = System.currentTimeMillis();
        try {
            //在timeout的时间范围内不断轮询锁
            while (System.currentTimeMillis() - startTime < LOCK_TIME * 1000L) {
                //锁不存在的话，设置锁并设置锁过期时间，即加锁
                if (this.redis.tryLock(getDistributeKey(identyKey), LOCK_TIME)) {
                    //设置锁过期时间是为了在没有释放锁的情况下锁过期后消失，不会造成永久阻塞
                    return true;
                }
                //短暂休眠，避免可能的活锁
                Thread.sleep(3L, new Random().nextInt(30));
            }
        } catch (Exception e) {
            throw new RuntimeException("locking error", e);
        }
        return false;
    }

    /**
     * 释放锁。仅应由 {@link #lock(String)} 返回 true 的持有者，按相同 identyKey 调用。
     */
    public void unlock(String identyKey) {
        try {
            redis.removeLock(getDistributeKey(identyKey));//直接删除
        } catch (Throwable e) {
            log.error("unlock distributed lock failed, key=" + identyKey, e);
        }
    }

    static final String getDistributeKey(String identy) {
        return identy + SEPERATE_TOKEN + LOCK;
    }
}
