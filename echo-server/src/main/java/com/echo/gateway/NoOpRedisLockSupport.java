package com.echo.gateway;

import com.aengine.util.lock.distributedLock.RedisLockSupport;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link RedisLockSupport} 的占位实现（本期不接 Redis）。
 *
 * <p>{@code PacketHandlerManager} 构造需要一个 {@link RedisLockSupport} 用于分布式锁；
 * BE-1 阶段尚未接入 Redis，这里提供一个永远“获取成功”的本地空实现，
 * 仅保证脚手架可启动。待接入 Redis 后替换为引擎真实的 Jedis 支撑实现。</p>
 */
@Slf4j
public class NoOpRedisLockSupport implements RedisLockSupport {

    @Override
    public boolean tryLock(String lockKey, int time) {
        return true;
    }

    @Override
    public void removeLock(String lockKey) {
        // no-op: 本期无 Redis，无需释放
    }
}
