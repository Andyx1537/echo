/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.aengine.util.lock.distributedLock;

/**
 * 不破坏的前提下获取Jedis支持的接口
 *
 */
public interface RedisLockSupport {

    /**
     * 获取redis支撑
     * @return 
     */
//    Redis getJedisControl();
    boolean tryLock(String lockKey ,int time);
//    public int setLockValue(String lockKey,String value);
//    public void expireLock(String lockKey,int second);
    void removeLock(String lockKey);
}
