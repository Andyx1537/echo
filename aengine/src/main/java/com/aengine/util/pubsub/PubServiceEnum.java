/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.aengine.util.pubsub;

/**
 * 发布订阅实现类型区分不同的订阅类型决定 服务状态
 *
 */
public enum PubServiceEnum {
    ONLINE,
    OFFLINE,
    BOTH;
}
