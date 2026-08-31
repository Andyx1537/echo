package com.aengine.persistence.annotation;

import java.lang.annotation.*;

/**
 * 列的定义
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@Documented
public @interface Column {

    String name();//列名

	boolean notNull() default true;//非空

	String defaultValue() default "____";

    boolean readOnly() default false;//只读（更新时不更新该字段）

    int length() default 255;//字符串长度

    boolean immutable() default false;//不可变长度

    String comment() default "";//说明

	String charset() default "";//编码
}
