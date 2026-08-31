package com.aengine.event;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EventHandleMethod {
    boolean value() default false;

    boolean async() default false;
}
