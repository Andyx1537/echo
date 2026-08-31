/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package com.aengine.util.pubsub;

import redis.clients.jedis.JedisPubSub;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 */
public interface PubSubSupportInterface {
	/**
	 * Sub and Push
	 */
    public void subscribe(JedisPubSub pubsubService,String... channels);
    
    public void increase(String msg, String key,ChannelInterface channel);
    
    public void increaseWithHead(String msg, String key,ChannelInterface channel,String messageHead);
    /**
     * fetch get delete save
     */
    public List<String> fetch(String key);
    
    public List<String> fetchAll(String playerId,String channel);
    
    public List<String> list(String key);
    
    public List<String> listAll(String playerId,String channel);
    
    public void delete(String key, String t);
    
    public void del(String key);
    
    public long save(String playerId, String key,ChannelInterface channel);
    /**
     * 当需要第二层keyList支持业务开发时，可以使用下述方法建立二层Key嵌套
     */
    public Set<String> getSubKeyByKey(String accountId,String channel);
    /**
     * 基本Redis方法（暂只支持List and HashMap）
     */
    public String lPop(String key);
    
    public long lPush(String key, int expire, String... values);
    
    public long lPush(String key, int expire, List<String> values);
    
    public List<String> lRange(String key, long start, long end);
    
    public void ltrim(String key,long start ,long end);
    
    public long lRem(String key, long count, String value);
    
    public long lLen(String key);
    
    public long hLen(String key);
    
    public Long hSet(String key, String field, String value);
    
    public List<String> hValues(String key);
    
    public Set<String> hKeys(String key);
    
    public Map<String, String> hGetAll(String key);
    
    public String hGet(String key, String field);
    
    public long hDel(String key, String... field);

	public void chatBeforeIncrease(String key, ChannelInterface chatChannelProcessor);
	
	public long hSetNX(String key, String field, String value);
}
