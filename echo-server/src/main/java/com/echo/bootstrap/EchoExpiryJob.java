package com.echo.bootstrap;

import com.aengine.scheduler.TriggerTask;
import com.echo.module.echo.EchoService;
import lombok.extern.slf4j.Slf4j;

/**
 * 过期回声清理定时任务（TECH-P1 §4.3）：由 {@link com.aengine.scheduler.Scheduler} + Cron 驱动。
 *
 * <p>Cron 为 6 段制（秒 分 时 日 月 周，见 Aengine {@code CronSequenceGenerator}）。
 * 默认每分钟第 0 秒触发一次，调用 {@link EchoService#purgeExpired()} 删除 {@code expireAt < now} 的回声。</p>
 */
@Slf4j
public class EchoExpiryJob extends TriggerTask {

    /** 默认 Cron：每分钟执行一次。 */
    public static final String DEFAULT_CRON = "0 * * * * *";

    private final EchoService echoService;

    public EchoExpiryJob(EchoService echoService) {
        this(DEFAULT_CRON, echoService);
    }

    public EchoExpiryJob(String cron, EchoService echoService) {
        super(cron);
        this.echoService = echoService;
    }

    @Override
    public void doTask() {
        try {
            echoService.purgeExpired();
        } catch (Exception e) {
            log.error("清理过期回声任务异常", e);
        }
    }
}
