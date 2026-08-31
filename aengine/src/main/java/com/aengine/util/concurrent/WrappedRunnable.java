package com.aengine.util.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 */
public abstract class WrappedRunnable implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(WrappedRunnable.class);

    /*
     * 临界时间
     */
    private final long threshold;

    private final boolean warning;

    protected WrappedRunnable(long threshold, boolean warning) {
        this.threshold = threshold;
        this.warning = warning;
    }

    protected WrappedRunnable() {
        this(100, true);
    }

    protected WrappedRunnable(boolean warning) {
        this(100, warning);
    }

    @Override
    public void run() {
        long time = System.currentTimeMillis();
        try {
            execute();
        } catch (Exception e) {
            log.error("execute runnable error", e);
        } finally {
            if (warning) {
                time = System.currentTimeMillis() - time;
                MethodCalledStatistic.handleStats(this.getClass(), "run", time);
                if (time > threshold) {
                    log.warn(this + ".Run:" + time + "ms.");
                }
            }

        }
    }

    /**
     * 执行一个任务
     */
    protected abstract void execute();

    @Override
    public String toString() {
        return this.getClass().getName();
    }
}
