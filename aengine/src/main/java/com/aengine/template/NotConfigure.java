package com.aengine.template;

import java.lang.annotation.*;

/**
 * 标志该字段不需要配置
 * 
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface NotConfigure {

}
