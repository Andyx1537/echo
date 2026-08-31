package com.aengine.util.pubsub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPubSub;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 */
public class PubSubService extends JedisPubSub {

    private static Logger log = LoggerFactory.getLogger(PubSubService.class);

    private static Map<String, ChannelInterface> pubMap = new ConcurrentHashMap<>();

    private static Map<String, Map<String, Method>> headMessageMap = new ConcurrentHashMap<>();// <channel,
    // <head,method>>

    private static Map<String, Method> messageMap = new ConcurrentHashMap<>();// <channel : method>

    private static final String specilSplit = "_&#&#&_";

    //	private static JedisPool pool;
    private static PubSubService singleton;

    private static Map<String, Boolean> subscribeCache = new HashMap<>();
    private static PubSubSupportInterface redis;

    public static void init(PubSubSupportInterface redisInit, Collection<ChannelInterface> collection) throws Exception {
        singleton = new PubSubService();
        redis = redisInit;
        for (ChannelInterface channel : collection) {
            int modifiers = channel.getClass().getModifiers();
            if (Modifier.isAbstract(modifiers) || Modifier.isInterface(modifiers))//忽略接口及抽象类
                continue;
            subscribeCache.put(channel.getChannel(), false);
            pubMap.put(channel.getChannel(), channel);
            // 拿下面的方法头信息
            Map<String, Method> methods = null;
            methods = new HashMap<>();
            for (Method method : channel.getClass().getDeclaredMethods()) {
                if (method.getAnnotation(ChannelMessageProcess.class) == null) {
                    continue;
                }
                if (!method.getAnnotation(ChannelMessageProcess.class).value().equals("")) {
                    methods.put(method.getAnnotation(ChannelMessageProcess.class).value(), method);
                } else {
                    messageMap.put(channel.getChannel(), method);
                }
            }
            headMessageMap.put(channel.getChannel(), methods);
        }
        // subscribe channel
        subChannel();
    }

    public void processRegistSuccess(String channel) {
        ChannelInterface processor = pubMap.get(channel);
        processor.onSuccess(new ArrayList<>(pubMap.keySet()));
        subscribeCache.put(channel, true);
        if (processor != null) {
            switch (processor.getServiceType()) {
                case ONLINE:
                    break;
                case BOTH:
                    break;
                case OFFLINE:
                    break;
            }
        }
    }

    /**
     * 发布频道
     *
     * @param channel
     */
    public static void pubChannel(String message, String... channels) {
        for (String channel : channels) {
            try {
                send(channel, message, "");
            } catch (Exception e) {
                log.info("", e);
            }
        }
    }

    private static void subChannel() throws Exception {
        // 不支持自动订阅,一个pool只能保持八个Jedis，换成订阅只能订阅八次.阻塞式订阅
        redis.subscribe(singleton, subscribeCache.keySet().toArray(new String[]{}));

        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
//            thread.interrupt();
        }

        for (String c : subscribeCache.keySet()) {
            if (!subscribeCache.get(c)) {
                // 失败处理
                log.error("Subscribe Failure Channel : " + c);
                throw new Exception(c);
            }
        }
    }

    /**
     * 关闭订阅
     */
    public static void shutdown() {
        singleton.unsubscribe(subscribeCache.keySet().toArray(new String[]{}));
    }

    static void send(String channel, String message, String key) {
        // log.error("send message on channel :" + channel + ", message :" + message);
        redis.increase(message, key, pubMap.get(channel));
    }

    static void send(String channel, String message, String key, String descInfo) {
        String messageWitHead = descInfo + specilSplit + message;
        redis.increaseWithHead(message, key, pubMap.get(channel), messageWitHead);
    }

    public static PubSubSupportInterface getRedisManager() {
        return redis;
    }

    @Override
    public void onMessage(String channel, String message) {
        // log.error("receive message on channel:" + channel + ",message:" + message);
        if (!pubMap.containsKey(channel)) {
            return;
        }
        ChannelInterface c = pubMap.get(channel);
        if (message.contains(specilSplit)) {
            String[] messages = message.split(specilSplit);
            try {
                Method method = headMessageMap.get(channel).get(messages[0]);
                if (method == null) {
//                    log.info("Channel Class not receive method with head " + messages[0] + " :" + c.getClass().getSimpleName());
                    return;
                }
                method.invoke(c, messages[1]);
            } catch (Exception e) {
                log.error("Error from headMessage Analysis", e);
            }
        } else {
            Method method = messageMap.get(channel);
            if (method == null) {
//                log.warn("Channel Class not receive method:" + c.getClass().getSimpleName());
                return;
            }
            try {
                method.invoke(c, message);
            } catch (Exception e) {
                log.error("Error from Message Analysis", e);
            }

        }
    }

    @Override
    public void onSubscribe(String channel, int subscribedChannels) {
        processRegistSuccess(channel);
    }
}
