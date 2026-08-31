package com.aengine.cache;

/**
 * 缓存事件监听器
 *
 */
public interface ICacheListener<K, V> {

	/**
	 * 当缓存溢出时调用
	 *
	 * @param k         键
	 * @param v         值
	 */
	void onEviction(K k, V v);
}
