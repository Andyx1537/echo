package com.aengine.network.support;

import java.lang.annotation.*;

/**
 *
 * PlayerSession合法性检查的方法
 *
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ValidateMethod {
}
