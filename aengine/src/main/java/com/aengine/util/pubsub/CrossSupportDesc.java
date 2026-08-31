/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package com.aengine.util.pubsub;

import java.lang.annotation.*;

/**
 * 跨服 支持 channel 标识符
 *  
 * 粮草结算
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrossSupportDesc {
    String value();
}


