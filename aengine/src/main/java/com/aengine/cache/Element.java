package com.aengine.cache;

/**
 * 缓存的存储单元，由一对key，value组成
 *
 */
public class Element<K, V>{

	private final K key;

	private final V value;

	/*
	 * 创建时间
	 */
	private volatile long createTime;

	/*
	 * 上次访问时间
	 */
	private volatile long lastTime;

	/*
	 * 存活时间
	 */
	private int timeToLive;

	/*
	 * 空闲时间
	 */
	private int timeToIdle;

	protected Element(K key, V value, int timeToIdle, int timeToLive) {
		this.key = key;
		this.value = value;
		this.createTime = System.currentTimeMillis();
		this.lastTime = this.createTime;
		this.timeToIdle = timeToIdle;
		this.timeToLive = timeToLive;
	}

	/**
	 * 缓存是否过期
	 *
	 * @return      true过期，false未过期
	 */
	public boolean isExpire() {
		long now = System.currentTimeMillis();
		return (timeToLive > 0 && createTime + timeToLive * 1000 < now)
				|| (timeToIdle > 0 && lastTime + timeToIdle * 1000 < now);
	}

	protected void updateLastTime() {
		this.lastTime = System.currentTimeMillis();
	}

	public K getKey() {
		return key;
	}

	public V getValue() {
		return value;
	}
}
