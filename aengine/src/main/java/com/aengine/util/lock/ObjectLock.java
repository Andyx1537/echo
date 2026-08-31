package com.aengine.util.lock;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 对象锁
 *
 */
public class ObjectLock extends ReentrantLock implements Comparable<ObjectLock> {

    /**
	 * 
	 */
	private static final long serialVersionUID = 1346258809191549338L;
	/**
     * 锁定对象的类型
     */
    private final Class<?> clz;

    private static AtomicLong seed = new AtomicLong(0);

    private long identity;

    /**
     * 构造指定对象的对象锁
     *
     * @param object 获取锁的对象实例
     */
    public ObjectLock(Object object) {
        this(object, false);
    }

    /**
     * 构造指定对象的对象锁
     *
     * @param object 获取锁的对象实例
     * @param fair   {@link ReentrantLock#isFair()}
     */
    public ObjectLock(Object object, boolean fair) {
        super(fair);
        clz = object.getClass();
        identity = seed.incrementAndGet();
    }

    @Override
    public int compareTo(ObjectLock o) {
    	return Long.compare(identity, o.identity);
    }
}
