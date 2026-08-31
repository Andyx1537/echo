package com.aengine.util.pubsub;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 消息保存实现
 * 
 *
 */
public class RedisActiveMessenger {
	private final JedisPool pool;

	protected int length = 0;

	protected int saveTime = 0;
	
	protected int keySize = 0;

	public RedisActiveMessenger(String ip, int port) {
		JedisPoolConfig config = new JedisPoolConfig();
		this.pool = new JedisPool(config, ip, port);
	}
	
	public void setChannel(ChannelInterface channel) {
		this.length = channel.getChannelListSize();
		this.saveTime = channel.getChannelCacheTime();
		this.keySize = channel.getChannelKeySize();
	}

	public List<String> fetch(String key) {
		List<String> list = lRange(key, 0, -1);
		lPop(key);
		return list;
	}

	public List<String> fetchAll(String playerId,String channel) {
		List<String> list = new ArrayList<>();
		for (String key : hKeys(channel + "_" + playerId)) {
			list.addAll(lRange(key, 0, -1));
			hDel(channel + "_" + playerId, key);
			delete(key);
		}
		return list;
	}

	public List<String> list(String key) {
		List<String> list = lRange(key, 0, -1);
		return list;
	}

	public List<String> listAll(String playerId,String channel) {
		List<String> list = new ArrayList<>();
		for (String key : hKeys(channel + "_" + playerId)) {
			if (lRange(key, 0, -1).isEmpty()) {
				hDel(channel + "_" + playerId, key);
			} else {
				list.addAll(lRange(key, 0, -1));
			}
		}
		return list;
	}

	public void increase(String msg, String key,String channel){
		if (key != null) {//not to save
			lPush(key, saveTime, msg);
			if (length > 0 && lLen(key) > length) {
				ltrim(key, 0, length - 1);
			}
		}
		publish(channel, msg);
	}
	
	public void increaseWithHead(String msg, String key,String channel,String messageHead){
		if (key != null) {//not to save
			lPush(key, saveTime, msg);
			if (length > 0 && lLen(key) > length) {
				ltrim(key, 0, length - 1);
			}
		}
		publish(channel, messageHead);
		
	}

	public void delete(String key) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			jedis.del(key);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}

	public void delete(String key, String t) {
		lRem(key, 1, t);
	}

	public long save(String playerId, String key,String channel){
		long result = hSet(channel + "_" + playerId, key,System.currentTimeMillis()+"");
		if(result == 0)
			return result;
		
		int length = hKeys(channel + "_" + playerId).size() - keySize;
		if (keySize > 0 && length > 0) {
			for(String value : hKeys(channel + "_" + playerId)) {
				if(lRange(value, 0, -1).isEmpty()) {
					hDel(channel + "_" + playerId, key);
					length--;
				}else if(ttl(value) == -2) {
					hDel(channel + "_" + playerId, key);
					length--;
				}
			}	
		}
		Map<String ,String> keyList = hGetAll(channel + "_" + playerId);
		if (keySize > 0 && length > 0) { //当数据还是超过限制时，删除最旧的信息
			String last_save = null;
			long time = 0;
			for(String value : keyList.keySet()) {
				if(last_save == null) {
					last_save = value;
					time = Long.valueOf(keyList.get(value));
				}else if(time > Long.valueOf(keyList.get(value))){
					last_save = value;
					time = Long.valueOf(keyList.get(value));
				}
			}
			hDel(channel + "_" + playerId, last_save);
		}
		
		return result;
	}

	public Set<String> getKeyByAccountId(String accountId,String channel) {
		return hKeys(channel + "_" + accountId);
	}
	

	public void chatBeforeIncrease(String key,String channel){
		String[] ids = key.split("_");
		for (String playerId : ids) {
			if (!playerId.equals(channel))
				save(playerId, key,channel);
		}
	}
	
	public void messageAck(String key , List<String> messageList) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			for(String message : messageList) {
				jedis.lpush(key, message);
			}
		} finally {
			if (jedis != null)
				jedis.close();
		}
		
	}
	
	/**
	 * see {@link Jedis#llen(String)}
	 */
	public long lLen(String key) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.llen(key);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#lpop(String)}
	 */
	public void lPop(String key) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			jedis.lpop(key);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#lpush(String, String...)}
	 */
	public long lPush(String key, int expire, String... values) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			long result = jedis.lpush(key, values);
			if (expire > 0)
				jedis.expire(key, expire);
			return result;
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#lrange(String, long, long)}
	 */
	public List<String> lRange(String key, long start, long end) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.lrange(key, start, end);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#rpop(String)}
	 */
	public void ltrim(String key,long start ,long end) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			jedis.ltrim(key, start, end);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#lrem(String, long, String)}
	 */
	public void lRem(String key, long count, String value) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			jedis.lrem(key, count, value);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#hdel(String, String...)}
	 */
	public void hDel(String key, String... field) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			jedis.hdel(key, field);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}

	/**
	 * see {@link Jedis#hget(String, String)}
	 */
	public String hGet(String key, String field) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.hget(key, field);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}

	/**
	 * see {@link Jedis#hgetAll(String)}
	 */
	public Map<String, String> hGetAll(String key) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.hgetAll(key);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}

	/**
	 * see {@link Jedis#hkeys(String)}
	 */
	public Set<String> hKeys(String key) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.hkeys(key);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}

	/**
	 * see {@link Jedis#hvals(String)}
	 */
	public List<String> hValues(String key) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.hvals(key);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}

	/**
	 * see {@link Jedis#hlen(String)}
	 */
	public long hLen(String key) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.hlen(key);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}

	/**
	 * see {@link Jedis#hset(String, String, String)}
	 */
	public Long hSet(String key, String field, String value) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.hset(key, field, value);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#zadd(String, Map<String, Double>)}
	 */
	public void zAdd(String key, Map<String, Double> scoreMembers) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			jedis.zadd(key, scoreMembers);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#zadd(String, double, String)}
	 */
	public void zAdd(String key, double score , String value) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			jedis.zadd(key, score , value);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#zcard(String)}
	 */
	public long zCard(String key) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.zcard(key);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#zcount(String, double, double)}
	 */
	public long zCount(String key ,double minScore , double maxScore) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.zcount(key, minScore, maxScore);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#zincrby(String, double, String)}
	 */
	public double zIncrby(String key, double score , String value) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.zincrby(key, score, value);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#zrange(String, Integer, Integer)}
	 */
	public Set<String> zRange(String key, int start , int end) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return new LinkedHashSet<>(jedis.zrange(key, start, end));
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#zrangeByScore(String, double, double)}
	 */
	public Set<String> zRangeByScore(String key, double min , double max) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return new LinkedHashSet<>(jedis.zrangeByScore(key, min, max));
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#zrank(String, String)}
	 */
	public long zRank(String key, String value) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.zrank(key, value);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#zrem(String,String...)}
	 */
	public void zRem(String key, String... values) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			jedis.zrem(key, values);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#zremrangeByScore(String, double, double)}
	 */
	public long zRemrangeByScore(String key, double start , double end) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.zremrangeByScore(key, start, end);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#zremrangeByRank(String, long, long)}
	 */
	public long zRemrangeByRank(String key, long start , long end) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.zremrangeByRank(key, start, end);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#zrevrange(String, long, long)}
	 */
	public Set<String> zRevrange(String key, long start , long end) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return new LinkedHashSet<>(jedis.zrevrange(key, start, end));
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#zrevrangeByScore(String, long, long)}
	 */
	public Set<String> zRevrangeByScore(String key, long start , long end) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return new LinkedHashSet<>(jedis.zrevrangeByScore(key, start, end));
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#zRevank(String, String)}
	 */
	public long zRevank(String key, String value) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.zrevrank(key, value);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	/**
	 * see {@link Jedis#zScore(String, String)}
	 */
	public double zScore(String key, String value) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.zscore(key, value);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	public void subscribe(JedisPubSub pubsub, String ...channels) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			jedis.subscribe(pubsub, channels);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	public void publish(String channel, String message) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			jedis.publish(channel, message);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}
	
	public Long ttl(String key) {
		Jedis jedis = null;
		try {
			jedis = pool.getResource();
			return jedis.ttl(key);
		} finally {
			if (jedis != null)
				jedis.close();
		}
	}

}
