package com.aengine.util.lock.distributedLock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分布式锁单元测试：用 Mockito mock 掉 RedisLockSupport（外部 Redis 依赖），
 * 仅验证加锁/解锁的调用逻辑。
 */
class DistributedLockTest {

    @Test
    void lockSucceedsWhenRedisGrantsLock() {
        RedisLockSupport support = mock(RedisLockSupport.class);
        when(support.tryLock(anyString(), anyInt())).thenReturn(true);

        DistributedLock lock = new DistributedLock(support);
        boolean locked = lock.lock("player_1");

        assertThat(locked).isTrue();
        verify(support).tryLock(eq("player_1_lock"), eq(10));

        lock.unlock("player_1");
        verify(support).removeLock(eq("player_1_lock"));
    }

    @Test
    void unlockRemovesTheGivenKey() {
        // 新契约：unlock 仅由持锁者按相同 key 调用，直接删除该 key 对应的锁（不依赖任何共享标志）
        RedisLockSupport support = mock(RedisLockSupport.class);
        DistributedLock lock = new DistributedLock(support);

        lock.unlock("player_1");

        verify(support).removeLock(eq("player_1_lock"));
    }

    @Test
    void lockAndUnlockAreScopedPerKeyOnSharedInstance() {
        // 同一实例被多 key / 多线程复用时，unlock 只删自己的 key，不受其它 key 的加锁状态影响。
        // 这正是历史实例级 boolean 标志会引入的误删 bug，已通过无状态实现修复。
        RedisLockSupport support = mock(RedisLockSupport.class);
        when(support.tryLock(anyString(), anyInt())).thenReturn(true);
        DistributedLock lock = new DistributedLock(support);

        assertThat(lock.lock("a")).isTrue();
        lock.unlock("b");

        verify(support).removeLock(eq("b_lock"));
        verify(support, never()).removeLock(eq("a_lock"));
    }
}
