package com.aengine.persistence.annotation;

import java.lang.annotation.*;

/**
 * 仓储接口的配置
 *
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CRepository {

	/**
	 * 数据源
	 */
	String source();

	/**
	 * 缓存的最大个数
	 */
	int maxElements() default 100000;

	/**
	 * 缓存的空闲时间（单位：秒）
	 */
	int timeToIdle() default 1800;

	/**
	 * 缓存的存活时间（单位：秒）
	 */
	int timeToLive() default 0;

	/**
	 * 异步写入和删除
	 */
	boolean async() default false;

	/*
	 * 批量保存数据的条数
	 */
	int batch() default 200;

	/*
	 * 保存间隔
	 */
	int interval() default 10;

	/*
	 * 延迟保存时间
	 */
	int delay() default 60;

	String rolling() default "none";
}
