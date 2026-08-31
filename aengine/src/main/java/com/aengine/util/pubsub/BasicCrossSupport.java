/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.aengine.util.pubsub;

import java.util.List;

/**
 * 基于跨服交互的需求 定制封装接口 主要解决 Server 之间的 动态交互问题, 其中包括, 带有逻辑优先级的 server1---servern
 * 的订阅发布处理, 针对订阅模式予以分割 主要解决实际需求中针对 扣除非公共资源的交互实现,并配置对应的有序处理 情况1: Server1 中的玩家 A
 * 与Servern 中的玩家B 发生交互,当涉及到 扣除A玩家 s 资源 为B 玩家增加 s资源时 所采取的封装体系. 其中包括: 频道订阅, 优先级设计,
 * 事务机制
 * 本机制只提供事务执行机制和保证序列 并未携带相关凭据数据,一般仍需在crossCenter中建立公共存储便于记录和更新相应数据
 * 
 */
public abstract class BasicCrossSupport<T> implements ChannelInterface {

    private CrossChainKey<T> key;

    protected void updateCacheContent(String json) {
    	PubSubSupportInterface redis = PubSubService.getRedisManager();
        String keyStr = this.getClass().getAnnotation(CrossSupportDesc.class).value();
        redis.hSet("crossSupport", keyStr, json);
    }

    protected String getCacheContent() {
    	PubSubSupportInterface redis = PubSubService.getRedisManager();
        String keyStr = this.getClass().getAnnotation(CrossSupportDesc.class).value();
        return redis.hGet("crossSupport", keyStr);
    }

    @SuppressWarnings("unchecked")
    private final void onMessage(String message) {
        key = CrossChainKey.getKey(message);
        if (key.getCurrentKey() == null) {
            return;
        }
        boolean needRoll = process(key.getCurrentKey());
        if (needRoll) {
            key.updateIndex();
            String keyStr = this.getClass().getAnnotation(CrossSupportDesc.class).value();
            ChannelInterface.sendMessage("crossSupport", message, keyStr);
        }
    }

    @Override
    public String getChannel() {
        return "crossSupport";
    }

    @Override
    public void onSuccess(List<String> messages) {
        for (String message : messages) {
            onMessage(message);
        }
    }

    public abstract boolean process(T t);

}
