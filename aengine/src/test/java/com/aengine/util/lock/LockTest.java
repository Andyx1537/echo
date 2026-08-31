package com.aengine.util.lock;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * util.lock 模块单元测试：ObjectLock / ReferenceCountedLockManager。
 */
class LockTest {

    @Test
    void objectLockIsReentrant() {
        ObjectLock lock = new ObjectLock(new Object());
        lock.lock();
        try {
            lock.lock();
            try {
                assertThat(lock.getHoldCount()).isEqualTo(2);
            } finally {
                lock.unlock();
            }
        } finally {
            lock.unlock();
        }
        assertThat(lock.isLocked()).isFalse();
    }

    @Test
    void objectLockComparableByIdentity() {
        ObjectLock a = new ObjectLock(new Object());
        ObjectLock b = new ObjectLock(new Object());
        assertThat(a.compareTo(b)).isLessThan(0);
        assertThat(b.compareTo(a)).isGreaterThan(0);
        assertThat(a.compareTo(a)).isZero();
    }

    @Test
    void referenceCountedLockManagerGuardsCriticalSection() throws InterruptedException {
        ReferenceCountedLockManager<String> manager = new ReferenceCountedLockManager<>();
        AtomicInteger counter = new AtomicInteger();
        int threads = 8;
        int loops = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < loops; i++) {
                    manager.lock("key");
                    try {
                        counter.incrementAndGet();
                    } finally {
                        manager.unlock("key");
                    }
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.get()).isEqualTo(threads * loops);
    }
}
