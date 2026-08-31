package com.aengine.scheduler;

import java.util.Date;

/**
 * 定时任务的上下文对象
 *
 */
public class TaskContext {
    private Date lastScheduleTime;
    private Date lastActualTime;
    private Date lastCompleteTime;

    public void update(Date scheduleTime, Date actualTime, Date completeTime) {
        if (scheduleTime != null)
            this.lastScheduleTime = scheduleTime;
        if (actualTime != null)
            this.lastActualTime = actualTime;
        if (completeTime != null)
            this.lastCompleteTime = completeTime;
    }

    public Date getLastScheduleTime() {
        return lastScheduleTime;
    }

    public Date getLastActualTime() {
        return lastActualTime;
    }

    public Date getLastCompleteTime() {
        return lastCompleteTime;
    }
}
