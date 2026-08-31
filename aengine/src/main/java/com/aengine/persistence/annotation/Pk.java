package com.aengine.persistence.annotation;

import java.lang.annotation.*;

/**
 * 主键
 *
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Pk {
    boolean auto() default true;
}
