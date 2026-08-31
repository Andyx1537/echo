package com.aengine.persistence.annotation;

import java.lang.annotation.*;

/**
 * 缓存
 *
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Cache {
	String[] columns();
}
