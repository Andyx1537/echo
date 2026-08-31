package com.aengine.network.support;

import com.aengine.network.netty.Packet;
import com.aengine.util.NetworkUtil;
import com.aengine.util.clazz.ClassUtil;
import com.aengine.util.concurrent.MethodCalledStatistic;
import com.aengine.util.lock.distributedLock.DistributedLock;
import com.aengine.util.lock.distributedLock.RedisLockSupport;
import com.aengine.util.ProtobufUtil;
import com.google.protobuf.Message;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 */
public class PacketHandlerManager {

    private static final Logger log = LoggerFactory.getLogger(PacketHandlerManager.class);

    private final Map<Integer, Method> handlers = new HashMap<>();

    private final Map<Integer, Method> retryHandlers = new HashMap<>();

    private final Map<Method, Object> instances = new HashMap<>();

    private final Map<Integer, Object> validateIds = new HashMap<>();

    private final Map<Integer, Message> messageProcessMap = new HashMap<>();//逻辑上,消息处理与udp 失败监控是两个不同的结构支撑,这里将这两个map独立出来.
    private final Map<Integer, Message> messageFailedMap = new HashMap<>();//

    private final Map<String, Method> httpHandlers = new HashMap<>();

    private DistributedLock lock = null;

    public PacketHandlerManager(RedisLockSupport lockSupport) {
        lock = new DistributedLock(lockSupport);
    }

    /**
     *
     * @param request
     * @param params
     * @return
     */
    public String handleHttpRequest(String request, Map<String, String> params) {
        Method method = httpHandlers.get(request);
        if (method == null) {
            return null;
        }
        Object instance = instances.get(method);
        if (instance == null) {
            return null;
        }
        try {
            String content = (String) method.invoke(instance, params);
            return content;
        } catch (Exception e) {
            log.error("handle http request failed", e);
            return null;
        }
    }

    public FullHttpResponse handleHttpGet(FullHttpRequest request) {
        String uri = request.uri();
        int index = uri.indexOf('?');
        String path;
        Map<String, String> params = new HashMap<>();
        if (index < 0) {
            path = uri;
        } else {
            path = uri.substring(0, index);
            if (index < uri.length()) {
                String param = uri.substring(index + 1);
                for (String pair : param.split("&")) {
                    String[] p = pair.split("=");
                    if (p.length == 2) {
                        params.put(p[0], p[1]);
                    }
                }
            }
        }
        Method method = httpHandlers.get(path);
        if (method == null) {
            return new DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        Object instance = instances.get(method);
        if (instance == null) {
            return new DefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
        try {
            String content = (String) method.invoke(instance, params);
            return new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.copiedBuffer(content, CharsetUtil.UTF_8));
        } catch (Exception e) {
            log.error("handle http request failed", e);
            return new DefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
    }

    protected void forward(IoSession session, Packet packet) throws Exception {
        session.protocol = packet.getHead() & Packet.HEAD_PROTOCOL_MASK;
        Method method = handlers.get(packet.getCmd());
        Message message = messageProcessMap.get(packet.getCmd());
        if (message == null) {
            log.info("cmd:" + packet.getCmd() + " not register in handler manager");
            return;
        }
        Message msg = null;
        switch (session.protocol) {
            case Packet.PROTOCOL_PROTOBUF: {
                msg = message.newBuilderForType().mergeFrom(packet.getBytes()).build();
                break;
            }
            case Packet.PROTOCOL_JSON: {
                String json = new String(packet.getBytes(), Charset.forName("UTF-8"));
                try {
                    Message.Builder builder = message.newBuilderForType();
                    ProtobufUtil.mergeJson(json, builder);
                    msg = builder.build();
                } catch (Exception e) {
                    log.error("parse json message failed, json=" + json + ", proto=" + message.getClass().getSimpleName(), e);
                }
                break;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug(msg.getClass().getSimpleName() + ":" + ProtobufUtil.toText(msg));
        }
        Object instance = instances.get(method);
        HandlerMethod handlerMethod = method.getAnnotation(HandlerMethod.class);
        if (!validateIds.containsKey(packet.getCmd()) && session.getIdenty() == null) {
            log.error("validateSession failed msgId :" + packet.getCmd());
            return;
        }
//        if (handlerMethod.value()) {
//            Method validateMethod = validate.get(instance);
//            if (validateMethod != null) {
//                validateMethod.invoke(instance, session, packet.getCmd(), msg);
//            }
//        }

        long time = System.currentTimeMillis();
        if (handlerMethod.value()) {
            boolean isLock = lock.lock(session.getIdenty());
            if (isLock) {
                try {
                    method.invoke(instance, session, msg);
                } finally {
                    lock.unlock(session.getIdenty());
                }
            }
        } else {
            method.invoke(instance, session, msg);
        }
//        method.invoke(instance, session, msg);
        time = System.currentTimeMillis() - time;
        MethodCalledStatistic.handleStats(instance.getClass(), method.getName(), time);
        HandlerStatistic.stats(msg.getClass(), time, packet.getBytes().length);
    }

    protected void onUdpFail(TransientSession session, Packet packet) {
        Method method = retryHandlers.get(packet.getCmd());
        Message message = messageFailedMap.get(packet.getCmd());
        if (message == null) {
            log.error("cmd:" + packet.getCmd() + " not register in handler manager");
            return;
        }
        try {
            Message msg = message.newBuilderForType().mergeFrom(packet.getBytes()).build();
            method.invoke(instances.get(method), session, msg);
        } catch (Exception e) {
            log.error("callback failed when udp retry failed", e);
        }
    }

    public void registHandlers(List<Object> handlers) {

        for (Object handler : handlers) {
            if (handler.getClass().getAnnotation(IPacketHandler.class) == null) {
                continue;
            }
            register(handler);
        }
    }

    public void register(Object packetHandler) {
        Method[] methods = packetHandler.getClass().getMethods();
        IPacketHandler packetAnno = packetHandler.getClass().getAnnotation(IPacketHandler.class);
        int[] noneedIds = packetAnno.noNeedCheckMessage();
        for (int noneedId : noneedIds) {
            Object obj = validateIds.putIfAbsent(noneedId, packetHandler);
            if (obj != null) {
                log.warn("noneedCheckSessionId checked! packetHandler :" + packetHandler.getClass());
            }
        }
        for (Method method : methods) {
            if (method.getAnnotation(HandlerMethod.class) != null) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 2) {
                    continue;
                }
                Class<?> mc = params[1];
                int cmd = 0;
                try {
                    cmd = NetworkUtil.getMessageID(mc);
                } catch (Exception e) {
                    log.error("parse cmd from class name(" + mc.getName() + ") failed", e);
                    continue;
                }
                if (cmd <= 0) {
                    continue;
                }
                Method m = ClassUtil.findMethod(mc, "getDefaultInstance");
                try {
                    Message message = (Message) m.invoke(null);
                    if (messageProcessMap.putIfAbsent(cmd, message) != null) {
                        log.error("duplicate handler method:" + packetHandler.getClass().getName() + "#" + method.getName());
                    } else {
                        handlers.put(cmd, method);
                        instances.put(method, packetHandler);
                    }
                } catch (Exception e) {
                    log.error("get type of handler failed", e);
                }
            } else if (method.getAnnotation(RetryFailMethod.class) != null) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 2) {
                    continue;
                }
                Class<?> mc = params[1];
                int cmd = 0;
                try {
                    cmd = NetworkUtil.getMessageID(mc);
                } catch (Exception e) {
                    log.error("parse cmd from class name(" + mc.getName() + ") failed", e);
                    continue;
                }
                if (cmd <= 0) {
                    continue;
                }
                Method m = ClassUtil.findMethod(mc, "getDefaultInstance");
                try {
                    Message message = (Message) m.invoke(null);
                    if (messageFailedMap.putIfAbsent(cmd, message) != null) {
                        log.error("duplicate handler method:" + packetHandler.getClass().getName() + "#" + method.getName());
                    } else {
                        retryHandlers.put(cmd, method);
                        instances.put(method, packetHandler);
                    }
                } catch (Exception e) {
                    log.error("get type of handler failed", e);
                }
            } //            else if (method.getAnnotation(ValidateMethod.class) != null) {
            //                Class<?>[] params = method.getParameterTypes();
            //                if (params.length != 3) {
            //                    continue;
            //                }
            //                validate.put(packetHandler, method);
            //            }
            else if (method.getAnnotation(HttpHandlerMethod.class) != null) {
                HttpHandlerMethod annotation = method.getAnnotation(HttpHandlerMethod.class);
                String uri = annotation.value();
                Class<?>[] params = method.getParameterTypes();
                if (method.getReturnType() != String.class) {
                    continue;
                }
                if (params.length != 1) {
                    continue;
                }
                if (params[0] != Map.class) {
                    continue;
                }
                try {
                    if (httpHandlers.putIfAbsent(uri, method) != null) {
                        log.error("duplicate http handler method:" + packetHandler.getClass().getName() + "#" + method.getName());
                    } else {
                        instances.put(method, packetHandler);
                    }
                } catch (Exception e) {
                    log.error("get type of handler failed", e);
                }
            }
        }
    }


}
