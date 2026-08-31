package com.aengine.network.support;

import java.lang.annotation.*;

/**
 *
 * 处理Protobuf消息的方法
 *
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HandlerMethod {
//
//	/**
//	 * 是否需要检查session的合法性，当且仅当tcp时有效
//	 *
//	 * @return
//	 */
//	boolean value() default true;
        /**
         * 当涉及redis数据改动时,需关心该状态,需要针对分布式原则进行锁止,需关心此状态
         */
        boolean value() default false;
}
