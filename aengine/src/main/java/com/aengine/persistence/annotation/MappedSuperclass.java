package com.aengine.persistence.annotation;

import java.lang.annotation.*;

/**
 * 映射父类属性到子类
 *
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MappedSuperclass {

}
