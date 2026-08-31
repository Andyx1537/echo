package com.aengine.event;

import com.aengine.util.concurrent.MethodCalledStatistic;
import com.aengine.util.lock.distributedLock.DistributedLock;
import com.aengine.util.lock.distributedLock.RedisLockSupport;
import com.aengine.util.thread.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 */
public class EventBusImpl {

    private static EventBusImpl instance;

    public static EventBusImpl getInstance() {
        return instance;
    }

    private static final Logger log = LoggerFactory.getLogger(EventBusImpl.class);

    /**
     * 注册的事件接收者
     */
    private final ConcurrentMap<Class<? extends IEvent>, ConcurrentMap<Method, Object>> receivers = new ConcurrentHashMap<>();

    private final ConcurrentMap<Method, Boolean> methods = new ConcurrentHashMap<>();

    private final ExecutorService pool;

    private final Set<Class<? extends IEvent>> distributeSet = new HashSet<>();

    private DistributedLock lock = null;

    public static void init(RedisLockSupport support, int threads) {
        instance = new EventBusImpl(support, threads);
    }

    private EventBusImpl(RedisLockSupport support, int threads) {
        lock = new DistributedLock(support);
        if (threads > 0)
            pool = Executors.newFixedThreadPool(threads, new NamedThreadFactory("event-bus"));
        else
            pool = null;
    }

    public void post(IEvent event) {
        ConcurrentMap<Method, Object> map = receivers.get(event.getClass());
        if (map != null)
            map.forEach((k, v) -> invokeMethod(event, k, v));
        Class<?> clazz = event.getClass().getSuperclass();
        for (;;) {
            if (clazz == null || clazz == Object.class)
                break;
            map = receivers.get(clazz);
            if (map != null) {
                map.forEach((k, v) -> invokeMethod(event, k, v));
                break;
            }
            clazz = clazz.getSuperclass();
        }
    }

    private static class AsyncEventTask implements Runnable {

        private final IEvent event;

        private final Method method;

        private final Object instance;

        public AsyncEventTask(IEvent event, Method method, Object instance) {
            this.event = event;
            this.method = method;
            this.instance = instance;
        }

        @Override
        public void run() {
            try {
                long st = System.currentTimeMillis();
                method.invoke(instance, event);
                st = System.currentTimeMillis() - st;
                MethodCalledStatistic.handleStats(instance.getClass(), method.getName(), st);
            } catch (Throwable t) {
                log.error("invoke event receiver method failed", t);
            }
        }
    }

    private void invokeMethod(IEvent event, Method method, Object instance) {
        try {
            if (!distributeSet.contains(event.getClass())) {
                if (methods.get(method)) {
                    pool.execute(new AsyncEventTask(event, method, instance));
                } else {
                    long st = System.currentTimeMillis();
                    method.invoke(instance, event);
                    st = System.currentTimeMillis() - st;
                    MethodCalledStatistic.handleStats(instance.getClass(), method.getName(), st);
                }
            } else {
                //开启分布式锁就不能启用异步事件处理，否则锁会失败
                boolean isLock = lock.lock(event.getIdenty());
                if (isLock) {
                    try {
                        long st = System.currentTimeMillis();
                        method.invoke(instance, event);
                        st = System.currentTimeMillis() - st;
                        MethodCalledStatistic.handleStats(instance.getClass(), method.getName(), st);
                    } finally {
                        lock.unlock(event.getIdenty());
                    }
                } else {
                    log.error("invoke event receiver method failed,can't get the distributed lock");
                }
            }

        } catch (Exception e) {
            log.error("invoke event receiver method failed", e);
        }
    }

    public void regist(Collection<Object> receiver) {
        for (Object obj : receiver) {
            regist(obj);
        }
    }

    @SuppressWarnings("unchecked")
    public void regist(Object receiver) {
        Method[] methods = receiver.getClass().getDeclaredMethods();
        for (Method method : methods) {
            if (method.getAnnotation(EventHandleMethod.class) == null) {
                continue;
            }
            EventHandleMethod eventHandlerAnno = method.getAnnotation(EventHandleMethod.class);

            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1) {
                continue;
            }
            Class<? extends IEvent> param = (Class<? extends IEvent>) params[0];
            receivers.computeIfAbsent(param, k -> new ConcurrentHashMap<>());
            if (eventHandlerAnno.value()) {//出现需要关注同步锁时.将缓存该类描述
                distributeSet.add(param);
            }
            ConcurrentMap<Method, Object> map = receivers.get(param);
            method.setAccessible(true);
            map.putIfAbsent(method, receiver);
            if (eventHandlerAnno.async()) {
                // 异步事件依赖线程池：若 EventBus 以 threads<=0 初始化，注册期即快速失败，避免派发时 NPE
                if (pool == null)
                    throw new IllegalStateException("event handler [" + receiver.getClass().getName() + "#"
                            + method.getName() + "] is async, but EventBus was initialized with threads<=0;"
                            + " call EventBusImpl.init(support, threads>0) to enable async handlers");
                this.methods.put(method, true);
            } else {
                this.methods.put(method, false);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void unregist(Object receiver) {
        Method[] methods = receiver.getClass().getDeclaredMethods();
        for (Method method : methods) {
            if (method.getAnnotation(EventHandleMethod.class) == null) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1) {
                continue;
            }
            Class<? extends IEvent> param = (Class<? extends IEvent>) params[0];
            receivers.computeIfAbsent(param, k -> new ConcurrentHashMap<>());
            ConcurrentMap<Method, Object> map = receivers.get(param);
            map.remove(method);
            this.methods.remove(method);
        }
    }
}
