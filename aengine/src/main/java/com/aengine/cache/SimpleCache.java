package com.aengine.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 基于ConcurrentHashMap实现的缓存
 *
 */
public class SimpleCache<K, V> implements ICache<K, V> {
    private final ConcurrentMap<K, Element<K, V>> map = new ConcurrentHashMap<>();

	/*
	 * 缓存的最大空闲时间
	 */
	private final int timeToIdle;

	/*
	 * 缓存的最大存活时间
	 */
	private final int timeToLive;

    public SimpleCache(int timeToIdle, int timeToLive) {
    	this.timeToIdle = timeToIdle;
    	this.timeToLive = timeToLive;
    	CacheManager.getInstance().addCache(this);
    }
    
    @Override
    public boolean containsKey(K key) {
        return map.containsKey(key);
    }

    @Override
    public void put(K key, V object) {
        if (key == null || object == null)
            return;
        map.put(key, new Element<>(key, object, timeToIdle, timeToLive));
    }

    @Override
    public V putIfAbsent(K key, V object) {
        if (key == null || object == null)
            return null;
        Element<K, V> element = map.putIfAbsent(key, new Element<>(key, object, timeToIdle, timeToLive));
        if (element == null) {
        	return null;
        } else {
        	element.updateLastTime();
        	return element.getValue();
        }
    }

	@Override
	public V computeIfAbsent(K key, Function<K, V> mappingFunction) {
		Element<K, V> element = map.computeIfAbsent(key,
				k -> new Element<>(key, mappingFunction.apply(key), timeToIdle, timeToLive));
		element.updateLastTime();;
		return element.getValue();
	}

	@Override
    public V get(K key) {
        Element<K, V> element = map.get(key);
        if (element == null)
            return null;
	    if (element.isExpire()) {
		    map.remove(key, element);
		    return null;
	    }
	    element.updateLastTime();
	    return element.getValue();
    }

    @Override
    public V remove(K key) {
        Element<K, V> element = map.remove(key);
        return element==null? null :element.getValue();
    }

    @Override
    public int size() {
        return map.size();
    }

	@Override
	public void removeAllExpired() {
		map.values().removeIf(Element::isExpire);
	}

	@Override
	public void clear() {
		map.clear();
	}

}
