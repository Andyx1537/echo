package com.aengine.scheduler;


import com.aengine.util.concurrent.WrappedRunnable;

import java.util.Date;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 由触发器触发的定时任务
 *
 */
public abstract class TriggerTask extends WrappedRunnable {
    private final Trigger trigger;
    private Scheduler scheduler;
    protected ScheduledFuture<?> currentFuture;

    public TriggerTask(Trigger trigger) {
        this.trigger = trigger;
    }

    public TriggerTask(String cronExpression) {
        this.trigger = new CronTrigger(cronExpression);
    }

    void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public abstract void doTask();

    synchronized ScheduledFuture<?> schedule() {
        Date scheduleTime = this.trigger.nextTime();
        if (scheduleTime == null)
            return null;
        trigger.context.update(scheduleTime, null, null);
        long delay = this.trigger.context.getLastScheduleTime().getTime() -
                    System.currentTimeMillis();
        this.currentFuture = this.scheduler.scheduleWithDelay(this, delay, TimeUnit.MILLISECONDS);
        return this.currentFuture;
    }

    @Override
    public void execute() {
        Date actualTime = new Date();
        try {
            doTask();
        } finally {
            Date completeTime = new Date();
            trigger.context.update(null, actualTime, completeTime);
            if (!this.currentFuture.isCancelled())
                schedule();
        }
    }
}
