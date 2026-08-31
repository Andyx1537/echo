package com.aengine.persistence.db;

import com.aengine.persistence.redis.Redis;
import com.aengine.util.RedisConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据库管理
 *
 */
public class DBManager {

    private static final Logger log = LoggerFactory.getLogger(DBManager.class);

    private static DBManager singleton;

    /*
     * 所有的db对象
     */
    private Map<String, DB> databases = new ConcurrentHashMap<>();
    private Map<Integer, Redis> redisCacheMap = new ConcurrentHashMap<>();
    private Map<String, Redis> redisCacheForName = new ConcurrentHashMap<>();
    private Redis redisCache = null;

    private DBManager() {
    }

    public static DBManager getInstance() {
        if (singleton != null) {
            return singleton;
        }
        synchronized (DBManager.class) {
            if (singleton == null) {
                singleton = new DBManager();
            }
            return singleton;
        }
    }

    /**
     * 将数据库添加到管理中
     *
     * @param db 数据库
     */
    public void add(DB db) {
        DB old = databases.putIfAbsent(db.getName(), db);
        if (old != null && old != db) {
            log.error("database " + db.getName() + " already exist in manager");
        }
    }

    /**
     * 将数据库添加到管理中
     *
     * @param properties 数据库配置
     * @return 数据库
     */
    public DB add(Properties properties) throws Exception {
        DB db = new DB(properties);
        add(db);
        return db;
    }

    public void addRedis(String name, Redis redis) {
        if (name == null) {
            redisCache = redis;
            redisCacheForName.put("default", redis);
        } else {
            redisCacheForName.put(name, redis);
        }
    }

    public void addRedis(List<RedisConfig> redisConfigs) throws Exception {
        for (RedisConfig config : redisConfigs) {
            String name = config.getName();
            Redis redis = config.getPassword() != null
                    ? new Redis(config.getIp(), config.getPort(), config.getPassword(), config.getIndex(), config.getThreads())
                    : new Redis(config.getIp(), config.getPort(), config.getIndex(), config.getThreads());
            try {
                if (config.getIndex() == 0 || config.getName().equals("default")) {
                    redisCache = redis;
                }
                redisCacheMap.put(config.getIndex(), redis);
            } catch (Exception ex) {//只有尝试可以转换成数字的才会被放入至该缓存中.用以创建逻辑分组db
                //

//                ex.printStackTrace();
            }
            //任何cache都将会放入该缓存
            redisCacheForName.put(name, redis);
        }

    }

//    public void addRedis(Properties properties) throws Exception {
//        ConcurrentHashMap<String, Redis> map = new ConcurrentHashMap<>();
//        String redisip = properties.getProperty("redis.ip");
//        int redisPort = Integer.parseInt(properties.getProperty("redis.port"));
//        Redis redis = new Redis(redisip, redisPort);
//        redisCache = redis;
//        redisCacheMap.put(0, redis);
//        if (properties.containsKey("redis1.ip")) {
//            String redis1ip = properties.getProperty("redis1.ip");
//            int redis1Port = Integer.parseInt(properties.getProperty("redis1.port"));
//            Redis redis1 = new Redis(redis1ip, redis1Port);
//            redisCacheMap.put(1, redis1);
//        }
//        if (properties.containsKey("redis2.ip")) {
//            String redis2ip = properties.getProperty("redis2.ip");
//            int redis2Port = Integer.parseInt(properties.getProperty("redis2.port"));
//            Redis redis2 = new Redis(redis2ip, redis2Port);
//            redisCacheMap.put(2, redis2);
//        }
//        if (properties.containsKey("redis3.ip")) {
//            String redis3ip = properties.getProperty("redis3.ip");
//            int redis3Port = Integer.parseInt(properties.getProperty("redis3.port"));
//            Redis redis3 = new Redis(redis3ip, redis3Port);
//            redisCacheMap.put(3, redis3);
//        }
//
//    }

    /**
     * 将数据库从管理中移除
     *
     * @param db 数据库
     */
    public void remove(DB db) {
        if (!databases.remove(db.getName(), db)) {
            log.error("database " + db.getName() + " not found in manager");
        }
    }

    /**
     * 移除管理中指定的数据库
     *
     * @param dbName 数据库的名字
     */
    public void remove(String dbName) {
        if (databases.remove(dbName) == null) {
            log.error("database " + dbName + " not found in manager");
        }
    }

    /**
     * 清空
     */
    public void clear() {
        databases.clear();
    }

    /**
     * 通过名字获取一个数据库
     *
     * @param dbName 数据库名字
     * @return 数据库
     */
    public DB get(String dbName) {
        return databases.get(dbName);
    }

    protected List<Runnable> hook = new ArrayList<>();

    private boolean stopped = false;

    public void addHook(Runnable task) {
        hook.add(task);
    }

    /**
     * 关闭
     */
    public synchronized void shutdown() {
        if (stopped) {
            return;
        }
        stopped = true;
        databases.forEach((k, v) -> v.shutdown());
        if (hook.size() == 0) {
            return;
        }
        hook.forEach(n -> {
            try {
                n.run();
            } catch (Throwable t) {
                log.error("", t);
            }
        });
    }

    public Redis getRedisCache() {
        return redisCache;
    }

    /**
     * //TODO redis 逻辑分配以当前配置的redis 的index max 为基准 当前暂时只支持4组redis
     * 的逻辑分配,以后需要再继续根据情况做扩展 index = obj.hashCode % 4
     *
     * @param redisName
     * @return
     */
    public Redis getRedisCache(String redisName) {
        return redisCacheMap.containsKey(redisName) ? redisCacheMap.get(redisName) : redisCache;
    }

    public Redis getRedisByIndex(String key) {
        int size = redisCacheMap.size();
        // 未配置分片 redis 时回退到默认 redisCache，避免 % 0 抛 ArithmeticException
        if (size == 0) {
            return redisCache;
        }
        int redisIndex = Math.abs(key.hashCode() % size);
        Redis redis = redisCacheMap.get(redisIndex);
        return redis != null ? redis : redisCache;
    }

    public Collection<Redis> allRedisInstance() {
        return redisCacheMap.values();
    }
}
