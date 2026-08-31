package com.aengine.persistence.redis;

import com.aengine.util.id.UUIDGenerateSupport;
import com.aengine.util.lock.distributedLock.RedisLockSupport;
import com.aengine.util.pubsub.ChannelInterface;
import com.aengine.util.pubsub.PubSubSupportInterface;
import com.aengine.util.thread.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.*;
import redis.clients.jedis.args.GeoUnit;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.params.SortingParams;
import redis.clients.jedis.resps.GeoRadiusResponse;
import redis.clients.jedis.resps.Tuple;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * redis辅助类
 *
 */
public class Redis implements PubSubSupportInterface, RedisLockSupport, UUIDGenerateSupport {

    private static final Logger log = LoggerFactory.getLogger(Redis.class);

    private final JedisPool pool;

    private static final String LOCK_VALUE = "lock";

    private int index;

    private ScheduledThreadPoolExecutor[] pools;

    public Redis(String ip, int port, int index) {
        this(ip, port, null, index, 0);
    }

    public Redis(String ip, int port, String password, int index) {
        this(ip, port, password, index, 0);
    }

    public Redis(String ip, int port, int index, int threads) {
        this(ip, port, null, index, threads);
    }

    public Redis(String ip, int port, String password, int index, int threads) {
        JedisPoolConfig config = new JedisPoolConfig();
        if (password != null)
            this.pool = new JedisPool(config, ip, port, Protocol.DEFAULT_TIMEOUT, password);
        else
            this.pool = new JedisPool(config, ip, port);
        this.index = index;
        if (threads > 0) {
            pools = new ScheduledThreadPoolExecutor[threads];
            NamedThreadFactory namedThreadFactory = new NamedThreadFactory("redis-saver");
            for (int i = 0; i < threads; i++) {
                pools[i] = new ScheduledThreadPoolExecutor(1, namedThreadFactory);
            }
        } else {
            this.pools = null;
        }
    }

    public Redis(Properties properties) {
        this(properties.getProperty("redis.ip"),
                Integer.parseInt(properties.getProperty("redis.port")),
                properties.containsKey("redis.password") ? properties.getProperty("redis.password") : null,
                0,
                properties.containsKey("redis.threads") ? Integer.parseInt(properties.getProperty("redis.threads")) : 0);
    }

    private int pool_index = 0;

    public ScheduledExecutorService getPool() {
        if (pools == null) {
            return null;
        }
        if (++pool_index >= pools.length) {
            pool_index = 0;
        }
        return pools[pool_index];
    }

    /**
     * see {@link Jedis#del(String)}
     */
    public void del(String key) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.del(key);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#set(String, String)}
     */
    public void set(String key, String value) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.set(key, value);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#get(String)}
     */
    public String get(String key) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.get(key);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#sort(String, SortingParams)}
     */
    public List<String> sort(String key, int offset, int count, boolean asc) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            SortingParams params = new SortingParams();
            params.limit(offset, count);
            if (asc) {
                params.asc();
            } else {
                params.desc();
            }
            return jedis.sort(key, params);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#incrBy(String, long)}
     */
    public Long incBy(String key, int step) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.incrBy(key, step);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#incr(String)}
     */
    public Long incr(String key) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.incr(key);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#decrBy(String, long)}
     */
    public Long decBy(String key, int step) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.decrBy(key, step);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * 当key值不存在时，增加对应的value，并设置expire秒后过期
     */
    public boolean setNX(String key, String value, int expire) {
        Jedis jedis = null;
        String result = null;
        try {
            jedis = pool.getResource();
            result = jedis.set(key, value, SetParams.setParams().nx().ex(expire));
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
        return "OK".equalsIgnoreCase(result);
    }

    /**
     * see {@link Jedis#setnx(String, String)}
     */
    public void setNX(String key, String value) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.setnx(key, value);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#mget(String...)}
     */
    public List<String> mGet(String... keys) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.mget(keys);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#mset(String...)}
     */
    public void mSet(String... keyValues) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.mset(keyValues);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#msetnx(String...)}
     */
    public void mSetNX(String... keyValues) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.msetnx(keyValues);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#hdel(String, String...)}
     */
    public long hDel(String key, String... field) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.hdel(key, field);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
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
            if (jedis != null) {
                jedis.close();
            }
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
            if (jedis != null) {
                jedis.close();
            }
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
            if (jedis != null) {
                jedis.close();
            }
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
            if (jedis != null) {
                jedis.close();
            }
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
            if (jedis != null) {
                jedis.close();
            }
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
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#hsetnx(String, String, String)}
     */
    public long hSetNX(String key, String field, String value) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.hsetnx(key, field, value);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#hsetnx(String, String, String)}
     */
    public long hSetNX(String key, String field, String value, int expireTime) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            long var = jedis.hsetnx(key, field, value);
            if (var != 0) {
                jedis.expire(key, expireTime);
            }
            return var;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * 如果key不存在。则设置过期时间
     * 如果key存在则直接设置。但并不设置过期时间
     * 暂时制作这样一个逻辑封装。后续可能
     *
     * @param key
     * @param field
     * @param value
     * @param expireTime
     * @return
     */
    public long hSetExpireWhenFirst(String key, String field, String value, int expireTime) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            long var;
            if (jedis.exists(key)) {
                var = jedis.hset(key, field, value);
            } else {
                var = jedis.hsetnx(key, field, value);
                if (var != 0) {
                    jedis.expire(key, expireTime);
                }
            }

            return var;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#hmset(String, Map)}
     */
    public void hmSet(String key, Map<String, String> kv) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.hmset(key, kv);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#hmget(String, String...)}
     */
    public List<String> hmGet(String key, String... fields) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.hmget(key, fields);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#lindex(String, long)}
     */
    public String lIndex(String key, int index) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.lindex(key, index);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
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
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#lpop(String)}
     */
    public String lPop(String key) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.lpop(key);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
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
            if (expire > 0) {
                jedis.expire(key, expire);
            }
            return result;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
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
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#lrem(String, long, String)}
     */
    public long lRem(String key, long count, String value) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.lrem(key, count, value);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#rpop(String)}
     */
    public void rPop(String key) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.rpop(key);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#rpop(String)}
     */
    public void Ltrim(String key, long start, long end) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.ltrim(key, start, end);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#rpush(String, String...)}
     */
    public void rPush(String key, String... values) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.rpush(key, values);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#sadd(String, String...)}
     */
    public long sAdd(String key, String... values) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.sadd(key, values);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#scard(String)}
     */
    public long sCard(String key) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.scard(key);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#smembers(String)}
     */
    public Set<String> sMembers(String key) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.smembers(key);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#sismember(String, String)}
     */
    public boolean sIsMember(String key, String member) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.sismember(key, member);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#srem(String, String...)}
     */
    public void sRem(String key, String... values) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.srem(key, values);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
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
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#zadd(String, double, String)}
     */
    public void zAdd(String key, double score, String value) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.zadd(key, score, value);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
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
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#zcount(String, double, double)}
     */
    public long zCount(String key, double minScore, double maxScore) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.zcount(key, minScore, maxScore);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#zincrby(String, double, String)}
     */
    public double zIncrby(String key, double score, String value) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.zincrby(key, score, value);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    public Set<String> zRange(String key, int start, int end) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return new LinkedHashSet<>(jedis.zrange(key, start, end));
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#zrangeByScore(String, double, double)}
     */
    public Set<String> zRangeByScore(String key, double min, double max) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return new LinkedHashSet<>(jedis.zrangeByScore(key, min, max));
        } finally {
            if (jedis != null) {
                jedis.close();
            }
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
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#zrem(String, String...)}
     */
    public void zRem(String key, String... values) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.zrem(key, values);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#zremrangeByScore(String, double, double)}
     */
    public long zRemrangeByScore(String key, double start, double end) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.zremrangeByScore(key, start, end);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#zremrangeByRank(String, long, long)}
     */
    public long zRemrangeByRank(String key, long start, long end) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.zremrangeByRank(key, start, end);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#zrevrange(String, long, long)}
     */
    public Set<String> zRevrange(String key, long start, long end) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return new LinkedHashSet<>(jedis.zrevrange(key, start, end));
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    public Set<String> zRevrangeByScore(String key, long start, long end) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return new LinkedHashSet<>(jedis.zrevrangeByScore(key, start, end));
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    public long zRevank(String key, String value) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.zrevrank(key, value);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    public double zScore(String key, String value) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.zscore(key, value);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * see {@link Jedis#keys(String)}
     */
    public Set<String> keys(String key) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            Set<String> keys = jedis.keys(key);
            return keys;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    @Override
    public void subscribe(JedisPubSub pubsub, String... channels) {
        Thread t = new Thread(() -> {
            // 正常 unsubscribe 时 jedis.subscribe 返回退出；连接异常时归还连接并重连
            while (!Thread.currentThread().isInterrupted()) {
                Jedis jedis = null;
                try {
                    jedis = pool.getResource();
                    jedis.subscribe(pubsub, channels);
                    return; // 被 unsubscribe，正常退出，不重连
                } catch (Exception e) {
                    log.error("redis subscribe error, will retry, channels=" + Arrays.toString(channels), e);
                } finally {
                    if (jedis != null) {
                        try {
                            jedis.close(); // 归还连接，避免连接泄漏耗尽连接池
                        } catch (Exception ignore) {
                        }
                    }
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "redis-subscribe");
        t.setDaemon(true);
        t.start();
    }

    public void publish(String channel, String message) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.publish(channel, message);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    @Override
    public boolean tryLock(String lockKey, int time) {
        return setNX(lockKey, LOCK_VALUE, time);
    }

    @Override
    public void removeLock(String lockKey) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.del(lockKey);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    @Override
    public void increase(String msg, String key, ChannelInterface channel) {
        if (key != null) {//not to save
            lPush(key, channel.getChannelCacheTime(), msg);
            if (channel.getChannelListSize() > 0 && lLen(key) > channel.getChannelListSize()) {
                ltrim(key, 0, channel.getChannelListSize() - 1);
            }
        }
        publish(channel.getChannel(), msg);
    }

    @Override
    public void increaseWithHead(String msg, String key, ChannelInterface channel, String messageHead) {
        if (key != null) {//not to save
            lPush(key, channel.getChannelCacheTime(), msg);
            if (channel.getChannelListSize() > 0 && lLen(key) > channel.getChannelListSize()) {
                ltrim(key, 0, channel.getChannelListSize() - 1);
            }
        }
        publish(channel.getChannel(), messageHead);
    }

    @Override
    public List<String> fetch(String key) {
        List<String> list = lRange(key, 0, -1);
        lPop(key);
        return list;
    }

    @Override
    public List<String> fetchAll(String playerId, String channel) {
        List<String> list = new ArrayList<>();
        for (String key : hKeys(channel + "_" + playerId)) {
            list.addAll(lRange(key, 0, -1));
            hDel(channel + "_" + playerId, key);
            del(key);
        }
        return list;
    }

    @Override
    public List<String> list(String key) {
        List<String> list = lRange(key, 0, -1);
        return list;
    }

    @Override
    public List<String> listAll(String playerId, String channel) {
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

    @Override
    public void delete(String key, String t) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.del(key);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    @Override
    public long save(String playerId, String key, ChannelInterface channel) {
        long result = hSet(channel + "_" + playerId, key, System.currentTimeMillis() + "");
        if (result == 0) {
            return result;
        }

        int length = hKeys(channel + "_" + playerId).size() - channel.getChannelKeySize();
        if (channel.getChannelKeySize() > 0 && length > 0) {
            for (String value : hKeys(channel + "_" + playerId)) {
                if (lRange(value, 0, -1).isEmpty()) {
                    hDel(channel + "_" + playerId, key);
                    length--;
                } else if (ttl(value) == -2) {
                    hDel(channel + "_" + playerId, key);
                    length--;
                }
            }
        }
        Map<String, String> keyList = hGetAll(channel + "_" + playerId);
        if (channel.getChannelKeySize() > 0 && length > 0) { //当数据还是超过限制时，删除最旧的信息
            String last_save = null;
            long time = 0;
            for (String value : keyList.keySet()) {
                if (last_save == null) {
                    last_save = value;
                    time = Long.valueOf(keyList.get(value));
                } else if (time > Long.valueOf(keyList.get(value))) {
                    last_save = value;
                    time = Long.valueOf(keyList.get(value));
                }
            }
            hDel(channel + "_" + playerId, last_save);
        }

        return result;
    }

    @Override
    public Set<String> getSubKeyByKey(String accountId, String channel) {
        return hKeys(channel + "_" + accountId);
    }

    @Override
    public void ltrim(String key, long start, long end) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            jedis.ltrim(key, start, end);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    public Long ttl(String key) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.ttl(key);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    @Override
    public long lPush(String key, int expire, List<String> values) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            long result = 0;
            for (String value : values) {
                result = jedis.lpush(key, value);
            }
            if (expire > 0) {
                jedis.expire(key, expire);
            }
            return result;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    public void chatBeforeIncrease(String key, ChannelInterface channel) {
        String[] ids = key.split("_");
        for (String playerId : ids) {
            if (!playerId.equals(channel.getChannel())) {
                save(playerId, key, channel);
            }
        }
    }

    private boolean isInit = false;

    @Override
    public long genLong(String key) {
        Jedis jedis = null;
        try {//每10秒钟产生1000w个long
            long defaultKeyStart = (System.currentTimeMillis() / 10000 * 10l + index) * 10000000;
            jedis = pool.getResource();
            if (jedis.get(key) == null && !isInit) {
                if (tryLock(key + "_lock", 10)) {
                    jedis.set(key, defaultKeyStart + "");
                    isInit = true;
                } else {
                    return -1;
                }
            }
//            if (lastUpdateMin != defaultKeyStart) {
//                lastUpdateMin = defaultKeyStart;
//                jedis.set(key, defaultKeyStart + "");
//            }//时间戳原则
            return jedis.incr(key);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
//        return -1L;
    }


    /**
     * 附近的人功能实现支持
     *
     * @param key
     * @param longitude
     * @param latitude
     * @param radius
     * @param unit
     * @return
     */
    public List<GeoRadiusResponse> georadius(String key, double longitude, double latitude, double radius, GeoUnit unit) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();

            return jedis.georadius(key, longitude, latitude, radius, unit);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
//        return null;

    }

    public long geoadd(String key, double longitude, double latitude, String member) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return jedis.geoadd(key, longitude, latitude, member);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    public Set<Tuple> zrangeWithScores(String key, long min, long max) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return new LinkedHashSet<>(jedis.zrangeWithScores(key, min, max));
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    public Set<Tuple> zrevrangeWithScores(String key, long min, long max) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
            return new LinkedHashSet<>(jedis.zrevrangeWithScores(key, min, max));
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }

    }


}
