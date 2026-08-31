package com.aengine.network.support;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IPacketHandler {

    String value() default "";

    int[] noNeedCheckMessage() default {};

}
