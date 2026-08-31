package com.aengine.util.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池任务队列溢出时默认的处理器
 *
 */
public class DefaultRejectedExecutionHandler implements RejectedExecutionHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultRejectedExecutionHandler.class);

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        if (executor.isShutdown())
            return;
        log.warn(r + "rejected by " + executor, new RejectedExecutionException());

        if (Thread.currentThread().getPriority() > Thread.NORM_PRIORITY)
            new Thread(r).start();
        else
            r.run();
    }
}
