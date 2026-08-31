package com.aengine.scheduler;

import com.aengine.util.concurrent.WrappedRunnable;
import com.aengine.util.thread.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * 定时器
 *
 */
public class Scheduler {
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);
    private final ScheduledExecutorService pool;
    private final ThreadFactory factory;

    private static Scheduler instance;

    public static void init(String name, int threads) {
        if (instance != null)
            return;
        instance = new Scheduler(name, threads);
    }

    public static Scheduler getInstance() {
        return instance;
    }

    public Scheduler(String name, int threads) {
        this.factory = new NamedThreadFactory(name);
        this.pool = new ScheduledThreadPoolExecutor(threads, factory);
    }

    /**
     * 执行{@link TriggerTask}
     *
     * @param task      待执行的任务
     * @return
     */
    public ScheduledFuture<?> schedule(TriggerTask task) {
        task.setScheduler(this);
        try {
            return task.schedule();
        } catch (RejectedExecutionException e) {
            log.error("scheduler reject task", e);
            return null;
        }
    }

    /**
     * 延时执行task
     *
     * @param task  待执行的任务
     * @param delay 延时
     * @param unit  时间单位
     * @return
     */
    public ScheduledFuture<?> scheduleWithDelay(WrappedRunnable task, long delay, TimeUnit unit) {
        try {
            return pool.schedule(task, delay, unit);
        } catch (RejectedExecutionException e) {
            log.error("scheduler reject task", e);
            return null;
        }
    }

    /**
     * 每隔period执行一次task
     *
     * @param task   待执行的任务
     * @param period 执行间隔
     * @param unit   时间单位
     * @return
     */
    public ScheduledFuture<?> scheduleAtFixedRate(WrappedRunnable task, long period, TimeUnit unit) {
        return scheduleAtFixedRate(task, 0, period, unit);
    }

    /**
     * 延时delay，每隔period执行一次task
     *
     * @param task   待执行的任务
     * @param delay  延时
     * @param period 执行间隔
     * @param unit   时间单位
     * @return
     */
    public ScheduledFuture<?> scheduleAtFixedRate(WrappedRunnable task, long delay, long period, TimeUnit unit) {
        try {
            return pool.scheduleAtFixedRate(task, delay, period, unit);
        } catch (RejectedExecutionException e) {
            log.error("scheduler reject task", e);
            return null;
        }
    }

    public void shutdown() {
        this.pool.shutdown();
    }
}
