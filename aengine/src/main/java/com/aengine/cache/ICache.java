package com.aengine.cache;

import java.util.function.Function;

/**
 * 缓存接口
 *
 */
public interface ICache<K, V> {

	/**
	 * 判断缓存是否存在这个key
	 *
	 * @param key	缓存Key
	 * @return      true有该key值，false没有
	 */
	boolean containsKey(K key);

	/**
	 * 将对象存入缓存中
	 *
	 * @param key    缓存Key
	 * @param object 存入缓存的对象
	 */
	void put(K key, V object);

	/**
	 * 当缓存中不存在key时，存入对象，否则返回key对应的对象
	 *
	 * @param key    缓存Key
	 * @param object 存入缓存的对象
	 * @return 缓存中Key对应的对象
	 */
	V putIfAbsent(K key, V object);

	/**
	 * 当缓存中不存在key时，创建一个新对象
	 *
	 * @param key
	 * @param mappingFunction
	 * @return
	 */
	V computeIfAbsent(K key, Function<K, V> mappingFunction);

	/**
	 * 从缓存中获得缓存对象
	 *
	 * @param key 缓存Key
	 * @return      缓存中Key对应的对象
	 */
	V get(K key);

	/**
	 * 从实体缓存中移除
	 *
	 * @param key 缓存Key
	 * @return      缓存中Key对应的对象
	 */
	V remove(K key);

	/**
	 * 当前缓存中的数据数量
	 * @return element个数
	 */
	int size();

	/**
	 * 删除所有过期的缓存
	 */
	void removeAllExpired();

	/**
	 * 清除所有缓存
	 */
	void clear();
}
