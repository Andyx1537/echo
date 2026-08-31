package com.aengine.scheduler;


import java.util.Date;
import java.util.TimeZone;

/**
 * Cron定时表达式触发器
 *
 */
public class CronTrigger extends Trigger {

    private final CronSequenceGenerator sequenceGenerator;

    public CronTrigger(String expression) {
        this(expression, TimeZone.getDefault());
    }

    public CronTrigger(String cronExpression, TimeZone timeZone) {
        this.sequenceGenerator = new CronSequenceGenerator(cronExpression, timeZone);
    }

    public Date nextTime() {
        Date date = context.getLastCompleteTime();
        if (date != null) {
            Date scheduled = context.getLastScheduleTime();
            if (scheduled != null && date.before(scheduled)) {
                date = scheduled;
            }
        } else {
            date = new Date();
        }

        return this.sequenceGenerator.next(date);
    }

}
