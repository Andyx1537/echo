package com.aengine.network.support;

import java.lang.annotation.*;

/**
 * 发送udp包失败时的回调
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RetryFailMethod {
}
