package com.aengine.scheduler;

import com.aengine.util.concurrent.WrappedRunnable;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scheduler 模块单元测试：CronSequenceGenerator / CronTrigger / Scheduler。
 */
class SchedulerTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    @Test
    void cronNextDailyMidnight() {
        CronSequenceGenerator generator = new CronSequenceGenerator("0 0 0 * * *", UTC);

        Calendar seed = new GregorianCalendar(UTC);
        seed.set(2020, Calendar.JANUARY, 1, 12, 0, 0);
        seed.set(Calendar.MILLISECOND, 0);

        Date next = generator.next(seed.getTime());

        Calendar expected = new GregorianCalendar(UTC);
        expected.set(2020, Calendar.JANUARY, 2, 0, 0, 0);
        expected.set(Calendar.MILLISECOND, 0);

        assertThat(next).isEqualTo(expected.getTime());
    }

    @Test
    void cronNextEveryTenSeconds() {
        CronSequenceGenerator generator = new CronSequenceGenerator("*/10 * * * * *", UTC);
        Calendar seed = new GregorianCalendar(UTC);
        seed.set(2020, Calendar.JANUARY, 1, 0, 0, 3);
        seed.set(Calendar.MILLISECOND, 0);

        Calendar c = new GregorianCalendar(UTC);
        c.setTime(generator.next(seed.getTime()));
        assertThat(c.get(Calendar.SECOND)).isEqualTo(10);
    }

    @Test
    void cronTriggerReturnsFutureTime() {
        CronTrigger trigger = new CronTrigger("0 0 0 * * *", UTC);
        Date next = trigger.nextTime();
        assertThat(next).isAfter(new Date());
    }

    @Test
    void invalidCronExpressionRejected() {
        try {
            new CronSequenceGenerator("invalid", UTC);
            assertThat(false).as("should have thrown").isTrue();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void schedulerRunsDelayedTask() throws InterruptedException {
        Scheduler scheduler = new Scheduler("test-scheduler", 1);
        CountDownLatch latch = new CountDownLatch(1);
        scheduler.scheduleWithDelay(new WrappedRunnable() {
            @Override
            protected void execute() {
                latch.countDown();
            }
        }, 10, TimeUnit.MILLISECONDS);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        scheduler.shutdown();
    }
}
