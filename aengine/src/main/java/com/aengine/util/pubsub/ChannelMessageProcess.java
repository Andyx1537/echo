package com.aengine.util.pubsub;

import java.lang.annotation.*;

/**
 * 
 *
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ChannelMessageProcess {

    String value() default "" ;//headInfo
}
