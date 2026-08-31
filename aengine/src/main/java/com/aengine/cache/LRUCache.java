package com.aengine.cache;


import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;

import java.util.function.Function;

/**
 * LRU缓存
 *
 */
public class LRUCache<K, V> implements ICache<K, V> {

	private final ConcurrentLinkedHashMap<K, Element<K, V>> lruMap;

	/*
	 * 缓存的最大空闲时间
	 */
	private final int timeToIdle;

	/*
	 * 缓存的最大存活时间
	 */
	private final int timeToLive;

	public LRUCache(int maxElements, int timeToIdle, int timeToLive, ICacheListener<K, V> listener) {
		ConcurrentLinkedHashMap.Builder<K, Element<K, V>> builder =
				new ConcurrentLinkedHashMap.Builder<K, Element<K, V>>()
						.maximumWeightedCapacity(maxElements);
		if (listener != null)
			builder.listener((k, element) -> listener.onEviction(k, element.getValue()));
		this.lruMap = builder.build();
		this.timeToIdle = timeToIdle;
		this.timeToLive = timeToLive;
		CacheManager.getInstance().addCache(this);
	}

	@Override
	public boolean containsKey(K key) {
		return lruMap.containsKey(key);
	}

	@Override
	public void put(K key, V object) {
		if (key == null || object == null)
			return;
		Element<K, V> element = new Element<>(key, object, timeToIdle, timeToLive);
		lruMap.put(key, element);
	}

	@Override
	public V putIfAbsent(K key, V object) {
		if (key == null || object == null)
			return null;
		Element<K, V> element = new Element<>(key, object, timeToIdle, timeToLive);
		Element<K, V> old = lruMap.putIfAbsent(key, element);
		if (old == null) {
			return null;
		} else {
			old.updateLastTime();
			return old.getValue();
		}
	}

	@Override
	public V computeIfAbsent(K key, Function<K, V> mappingFunction) {
		Element<K, V> element = lruMap.computeIfAbsent(key,
				k -> new Element<>(key, mappingFunction.apply(key), timeToIdle, timeToLive));
		element.updateLastTime();
		return element.getValue();
	}

	@Override
	public V get(K key) {
		Element<K, V> element = lruMap.get(key);
		if (element == null)
			return null;
		if (element.isExpire()) {
			lruMap.remove(key, element);
			return null;
		}
		element.updateLastTime();
		return element.getValue();
	}

	@Override
	public V remove(K key) {
		Element<K, V> element = lruMap.remove(key);
		return element == null ? null : element.getValue();
	}

	@Override
	public int size() {
		return lruMap.size();
	}

	@Override
	public void removeAllExpired() {
		lruMap.values().removeIf(Element::isExpire);
	}

	@Override
	public void clear() {
		lruMap.clear();
	}
}
