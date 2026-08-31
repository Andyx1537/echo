package com.aengine.util.lock;

/**
 * 带计数的引用对象
 *
 */
public interface ReferenceCounted {

    /**
     * 持有对象
     *
     * @return  对象有效时返回true，对象无效时返回false
     */
    boolean retain();

    /**
     * 释放对象
     *
     * @return  对象被释放返回true，对象没有被释放返回false
     */
    boolean release();
}
