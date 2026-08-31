package com.aengine.util.lock;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 抽象的引用计数
 *
 */
public abstract class AbstractReferenceCounted implements ReferenceCounted{
    private AtomicInteger counter = new AtomicInteger(1);

    @Override
    public boolean retain() {
        int count = counter.get();
        if (count < 1)
            return false;
        count = counter.incrementAndGet();
        return count >= 1;
    }

    @Override
    public boolean release() {
        int count = counter.decrementAndGet();
        if (count == 0)
            throw new RuntimeException("can not release a deallocate object");
        if (counter.compareAndSet(1, Integer.MIN_VALUE)) {
            deallocate();
            return true;
        }
        return false;
    }

    /**
     * 释放对象
     */
    protected abstract void deallocate();
}
