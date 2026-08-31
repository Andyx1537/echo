/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package com.aengine.util;

import lombok.Getter;
import lombok.Setter;

/**
 * 
 * cache_redis_ip=127.0.0.1 cache_redis_port=6379
 * 
 */
@Getter
@Setter
public class RedisConfig {

	private String ip;
	private int port;
	private String name;
	private int index;// 用以支持逻辑分组用
	private String password; // 密码
	private int threads;//延迟保存用线程池
}
