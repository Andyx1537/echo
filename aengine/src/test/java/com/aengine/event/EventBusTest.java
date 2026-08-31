package com.aengine.event;

import com.aengine.util.lock.distributedLock.RedisLockSupport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * event 模块单元测试：EventBusImpl 同步/异步分发，分布式事件用 Mockito mock 掉 Redis 锁。
 */
class EventBusTest {

    @Test
    void syncDispatchInvokesReceiver() {
        RedisLockSupport support = mock(RedisLockSupport.class);
        EventBusImpl.init(support, 0);
        SyncReceiver receiver = new SyncReceiver();
        EventBusImpl.getInstance().regist(receiver);

        EventBusImpl.getInstance().post(new MyEvent());
        EventBusImpl.getInstance().post(new MyEvent());

        assertThat(receiver.count.get()).isEqualTo(2);
    }

    @Test
    void unregisterStopsDispatch() {
        RedisLockSupport support = mock(RedisLockSupport.class);
        EventBusImpl.init(support, 0);
        SyncReceiver receiver = new SyncReceiver();
        EventBusImpl.getInstance().regist(receiver);
        EventBusImpl.getInstance().unregist(receiver);

        EventBusImpl.getInstance().post(new MyEvent());

        assertThat(receiver.count.get()).isZero();
    }

    @Test
    void asyncDispatchInvokesReceiver() throws InterruptedException {
        RedisLockSupport support = mock(RedisLockSupport.class);
        EventBusImpl.init(support, 2);
        AsyncReceiver receiver = new AsyncReceiver();
        EventBusImpl.getInstance().regist(receiver);

        EventBusImpl.getInstance().post(new MyEvent());

        long deadline = System.currentTimeMillis() + 5000;
        while (receiver.count.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(receiver.count.get()).isEqualTo(1);
    }

    @Test
    void distributedEventAcquiresRedisLock() {
        RedisLockSupport support = mock(RedisLockSupport.class);
        when(support.tryLock(anyString(), anyInt())).thenReturn(true);
        EventBusImpl.init(support, 0);
        DistReceiver receiver = new DistReceiver();
        EventBusImpl.getInstance().regist(receiver);

        EventBusImpl.getInstance().post(new DistEvent());

        assertThat(receiver.count.get()).isEqualTo(1);
        verify(support).tryLock(eq("lock-key_lock"), anyInt());
        verify(support).removeLock(eq("lock-key_lock"));
    }

    static class MyEvent implements IEvent {
    }

    static class DistEvent implements IEvent {
        @Override
        public String getIdenty() {
            return "lock-key";
        }
    }

    static class SyncReceiver {
        final AtomicInteger count = new AtomicInteger();

        @EventHandleMethod
        public void onEvent(MyEvent event) {
            count.incrementAndGet();
        }
    }

    static class AsyncReceiver {
        final AtomicInteger count = new AtomicInteger();

        @EventHandleMethod(async = true)
        public void onEvent(MyEvent event) {
            count.incrementAndGet();
        }
    }

    static class DistReceiver {
        final AtomicInteger count = new AtomicInteger();

        @EventHandleMethod(value = true)
        public void onEvent(DistEvent event) {
            count.incrementAndGet();
        }
    }
}
