package com.echo.infra.safety;

import lombok.extern.slf4j.Slf4j;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 第一关（合规词表）的统一收口：<b>失败方向、配额、熔断、就绪判据</b>都只在这里定义一次。
 *
 * <h2>🔴 失败方向：一律「未通过」</h2>
 *
 * <p>超时、限流、鉴权失败、返回格式异常、熔断中 —— 全部 {@link ContentSafetyVerdict#failedClosed}。
 * 这一关的失效方向一旦反了，<b>整关就等于不存在，而且从代码上看不出来</b>：
 * 每次调用都返回"通过"，日志安静，指标漂亮。所以本类刻意把所有异常路径收在
 * {@link #inspect} 的一个 catch 里，各实现<b>不允许</b>自己兜底（见
 * {@link IContentSafetyClient} 的约定）。</p>
 *
 * <h2>🔴 就绪判据：真的调通过一次</h2>
 *
 * <p>{@link #isOperational()} 只在<b>最近一次真实调用成功</b>后才为 true。
 * 「配置齐了」「代码写完了」都不算 —— 那两种状态下 {@code S13} 开关必须继续打不开。
 * 一份填错的 endpoint 能让配置检查通过，但过不了这个判据。</p>
 *
 * <h2>降级方向：只能更严</h2>
 *
 * <p>配额用尽 → 拒绝（不是放行）。连续失败进熔断 → 熔断期内一律拒绝（不是放行）。
 * 🔴 本类<b>没有任何一条路径</b>会因为"服务不可用"而放行已配置状态下的内容。</p>
 *
 * <h2>⚠️ 未配置 ≠ 失败</h2>
 *
 * <p>未配置时返回 {@link ContentSafetyVerdict#skipped()}（跳过本关），而不是拒绝。
 * 这不是自相矛盾：未配置状态下 {@link #isOperational()} 为 false → 能力未就绪 →
 * {@code S13} 开关打不开 → 公开层<b>根本没有用户自由文本</b>。保护来自"功能没开"。
 * 反过来若未配置就拒绝一切，现有的宠物近况生成会全线挂掉，而那批内容并不是本关要防的东西。</p>
 */
@Slf4j
public final class ContentSafetyGate {

    private final ContentSafetyConfig config;
    private final IContentSafetyClient client;

    /** 🔴 就绪判据：最近一次真实调用是否成功。初始 false —— 没调通就是没就绪。 */
    private volatile boolean operational = false;
    private volatile long lastSuccessAt = 0L;

    // ---- 熔断 ----
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long circuitOpenUntil = 0L;

    // ---- 本进程限流（简单令牌桶：每秒重置计数）----
    private final AtomicInteger windowCount = new AtomicInteger();
    private volatile long windowStartSec = 0L;

    // ---- 指标 ----
    private final AtomicLong inspected = new AtomicLong();
    private final Map<ContentSafetyVerdict.Outcome, AtomicLong> byOutcome =
            new EnumMap<>(ContentSafetyVerdict.Outcome.class);
    private final Map<ContentSafetyException.Reason, AtomicLong> byFailReason =
            new EnumMap<>(ContentSafetyException.Reason.class);

    public ContentSafetyGate(ContentSafetyConfig config, IContentSafetyClient client) {
        this.config = config;
        this.client = client;
        for (ContentSafetyVerdict.Outcome o : ContentSafetyVerdict.Outcome.values()) {
            byOutcome.put(o, new AtomicLong());
        }
        for (ContentSafetyException.Reason r : ContentSafetyException.Reason.values()) {
            byFailReason.put(r, new AtomicLong());
        }
    }

    /**
     * 检测一段文本。
     *
     * <p>🔴 本方法<b>不抛异常</b>：所有异常都在这里变成「未通过」。调用方只看
     * {@link ContentSafetyVerdict#passed()}。</p>
     */
    public ContentSafetyVerdict inspect(String text) {
        if (!config.isConfigured()) {
            // ⚠️ 跳过，不是通过。isOperational() 仍为 false → 能力未就绪
            return record(ContentSafetyVerdict.skipped());
        }
        if (text == null || text.isBlank()) {
            return record(ContentSafetyVerdict.pass());
        }
        try {
            checkCircuit();
            checkQuota();
            ContentSafetyVerdict v = client.inspectText(text);
            if (v == null) {
                throw new ContentSafetyException(ContentSafetyException.Reason.MALFORMED_RESPONSE,
                        "客户端返回 null");
            }
            onSuccess();
            return record(v);
        } catch (ContentSafetyException e) {
            return record(onFailure(e));
        } catch (RuntimeException e) {
            // 🔴 兜住实现里漏出来的一切 RuntimeException。少了这一层，
            //    一个 NPE 就会沿调用栈上抛，被上游某个宽泛的 catch 变成"这条内容没问题"。
            return record(onFailure(new ContentSafetyException(
                    ContentSafetyException.Reason.TRANSPORT, "客户端未预期异常", e)));
        }
    }

    private void checkCircuit() throws ContentSafetyException {
        if (System.currentTimeMillis() < circuitOpenUntil) {
            throw new ContentSafetyException(ContentSafetyException.Reason.CIRCUIT_OPEN,
                    "内容安全服务熔断中，按未通过处理");
        }
    }

    /**
     * 本进程限流。
     *
     * <p>🔴 配额用尽 → <b>拒绝</b>。这是「降级只能更严」的落点：
     * 放行的话，攻击者只要把请求打到超过配额，后面的内容就全都不检查了 ——
     * 限流会变成一条<b>可主动触发</b>的绕过路径。</p>
     */
    private void checkQuota() throws ContentSafetyException {
        if (config.qps() <= 0) {
            return;
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        if (nowSec != windowStartSec) {
            synchronized (this) {
                if (nowSec != windowStartSec) {
                    windowStartSec = nowSec;
                    windowCount.set(0);
                }
            }
        }
        if (windowCount.incrementAndGet() > config.qps()) {
            throw new ContentSafetyException(ContentSafetyException.Reason.LOCAL_QUOTA,
                    "内容安全本地配额用尽（" + config.qps() + "/s），按未通过处理");
        }
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
        circuitOpenUntil = 0L;
        lastSuccessAt = System.currentTimeMillis();
        if (!operational) {
            operational = true;
            log.info("内容安全服务已调通（provider={}），第一关就绪", client.provider());
        }
    }

    private ContentSafetyVerdict onFailure(ContentSafetyException e) {
        byFailReason.get(e.reason()).incrementAndGet();
        // 本地配额与熔断短路不算"服务失败"，不该把熔断计数推得更高
        if (e.reason() != ContentSafetyException.Reason.LOCAL_QUOTA
                && e.reason() != ContentSafetyException.Reason.CIRCUIT_OPEN) {
            int fails = consecutiveFailures.incrementAndGet();
            if (fails >= config.breakerThreshold() && circuitOpenUntil <= System.currentTimeMillis()) {
                circuitOpenUntil = System.currentTimeMillis() + config.breakerCooldownMs();
                // 🔴 熔断期内是"一律拒绝"，不是"一律放行"
                log.error("内容安全服务连续失败 {} 次，熔断 {}ms（熔断期内一律按未通过处理）",
                        fails, config.breakerCooldownMs());
            }
            // 🔴 失败即视为未就绪：能力状态要能反映"现在其实没有保护"
            operational = false;
        }
        log.warn("内容安全检测失败（{}），按未通过处理: {}", e.reason(), e.getMessage());
        return ContentSafetyVerdict.failedClosed(e.reason().name());
    }

    private ContentSafetyVerdict record(ContentSafetyVerdict v) {
        inspected.incrementAndGet();
        byOutcome.get(v.outcome()).incrementAndGet();
        return v;
    }

    /**
     * 🔴 第一关是否<b>真的</b>就绪。
     *
     * <p>判据是「最近一次真实调用成功」，不是「配置齐了」也不是「代码写完了」。
     * 这个方法是 {@code S13} 治理能力探针的数据源。</p>
     */
    public boolean isOperational() {
        return operational;
    }

    /**
     * 启动期探活：发一条无害文本，把 {@link #isOperational()} 从 false 推到 true。
     *
     * <p>未配置时直接返回 false，不发请求。🔴 探活失败<b>不阻止启动</b> ——
     * 第一关未就绪的后果已经由 {@code S13} 开关承担（开关打不开），
     * 而让整个服务起不来会把一个"某功能不能开"的问题放大成"全站下线"。</p>
     */
    public boolean probe() {
        if (!config.isConfigured()) {
            log.info("内容安全服务未配置（ECHO_SAFETY_PROVIDER={}），第一关保持未就绪；"
                    + "S13 留一句话开关将无法打开", config.provider());
            return false;
        }
        ContentSafetyVerdict v = inspect("今天的阳光很好。");
        if (v.actuallyInspected()) {
            log.info("内容安全服务探活成功（provider={}）", client.provider());
            return true;
        }
        log.error("🔴 内容安全服务探活失败（provider={}, outcome={}）：第一关未就绪，"
                + "S13 留一句话开关将无法打开。请检查 ECHO_SAFETY_* 配置与网络连通性",
                client.provider(), v.outcome());
        return false;
    }

    /** 指标快照（供后台与健康检查）。 */
    public Map<String, Object> metrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", config.provider());
        m.put("configured", config.isConfigured());
        m.put("operational", operational);
        m.put("lastSuccessAt", lastSuccessAt);
        m.put("inspected", inspected.get());
        m.put("circuitOpen", System.currentTimeMillis() < circuitOpenUntil);
        Map<String, Long> outcomes = new LinkedHashMap<>();
        byOutcome.forEach((k, v) -> outcomes.put(k.name(), v.get()));
        m.put("byOutcome", outcomes);
        Map<String, Long> fails = new LinkedHashMap<>();
        byFailReason.forEach((k, v) -> {
            if (v.get() > 0) {
                fails.put(k.name(), v.get());
            }
        });
        m.put("byFailReason", fails);
        return m;
    }

    /** 🔴 按失败处理的次数。它涨说明服务在出问题，不说明有人在发违规内容。 */
    public long failedClosedCount() {
        return byOutcome.get(ContentSafetyVerdict.Outcome.FAILED_CLOSED).get();
    }

    public long blockedCount() {
        return byOutcome.get(ContentSafetyVerdict.Outcome.BLOCKED).get();
    }

    public long skippedCount() {
        return byOutcome.get(ContentSafetyVerdict.Outcome.SKIPPED_UNCONFIGURED).get();
    }
}
