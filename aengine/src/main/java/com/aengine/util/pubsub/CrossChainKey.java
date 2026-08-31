/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.aengine.util.pubsub;


import com.aengine.util.GsonUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨服专用的自备数据处理链
 *
 */
public final class CrossChainKey<T> {

    private List<T> keyList = new ArrayList<>();

    private int currentIndex = 0;//当前处理阶段

    public void add(T key) {
        keyList.add(key);
    }

    public void add(T... keys) {
        for (T t : keys) {
            keyList.add(t);
        }
    }
//    public void reverse() {
//        Collections.reverse(keyList);
//    }

    public void updateIndex() {
        currentIndex++;
    }

    public T getCurrentKey() {
        return keyList.size() > currentIndex ? keyList.get(currentIndex) : null;
    }

    public String getJson() {
        return GsonUtil.beanToJson(this);
    }

    static CrossChainKey getKey(String json) {
        return GsonUtil.jsonToBean(json, CrossChainKey.class);
    }
}
