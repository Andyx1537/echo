package com.aengine.persistence.annotation;

import java.lang.annotation.*;

/**
 * 表
 *
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Table {

    String name();//表名

    Index[] index() default {};//索引

	Cache[] cache() default {};//缓存

    String clusterBy() default "";//分表策略

    int cluster() default 0;//分表个数

    String comment() default "";//说明

	boolean autoCreate() default true;//是否自动建表

	String charset() default "utf8mb4";//编码
}
