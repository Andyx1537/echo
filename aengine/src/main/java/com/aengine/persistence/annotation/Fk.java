/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package com.aengine.persistence.annotation;

import java.lang.annotation.*;

/**
 * 外键索引
 * 
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Fk {
    Index.IndexType type() default Index.IndexType.NORMAL;// 索引类型
}
