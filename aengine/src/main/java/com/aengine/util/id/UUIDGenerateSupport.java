/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package com.aengine.util.id;

/**
 *
 */
public interface UUIDGenerateSupport {
    
    /**
     * 根据给定key 产生一个唯一id
     * @param key
     * @return 
     */
    public long genLong(String key);
}
