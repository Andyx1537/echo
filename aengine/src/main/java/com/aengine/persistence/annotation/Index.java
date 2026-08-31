package com.aengine.persistence.annotation;

import java.lang.annotation.*;

/**
 * 索引
 *
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Index {
    String name() default "";//索引名

    String[] columns();//索引包含的列

    IndexType type() default IndexType.NORMAL;//索引类型

    enum IndexType {
        NORMAL,//普通索引
        UNIQUE,//唯一索引
        FULLTEXT;//全文索引
    }
}
