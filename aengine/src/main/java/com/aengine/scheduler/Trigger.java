package com.aengine.scheduler;

import java.util.Date;

/**
 * 触发器
 *
 */
public abstract class Trigger {
    protected final TaskContext context = new TaskContext();

    /**
     * 获取下次触发的时间
     *
     * @return      下次触发的时间
     */
    abstract Date nextTime();
}
