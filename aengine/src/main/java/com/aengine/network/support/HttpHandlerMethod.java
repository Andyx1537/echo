package com.aengine.network.support;

import java.lang.annotation.*;

/**
 * 处理http请求的方法
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HttpHandlerMethod {

	/**
	 * 该方法拦截指定的uri
	 *
	 * @return
	 */
	String value() default "";
}
