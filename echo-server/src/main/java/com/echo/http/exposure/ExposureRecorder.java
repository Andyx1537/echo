package com.echo.http.exposure;

import com.aengine.util.id.IDGenerator;
import com.echo.infra.persistence.PgDb;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 曝光记账：实时缓存去重 + 异步批量落 {@code t_card_exposure}。
 *
 * <p>正确性与性能的分工（{@code TECH-DESIGN §3.3 / §3.10.5}）：</p>
 * <ul>
 *   <li><b>正确性放在 DB 唯一键上</b>：{@code t_card_exposure_uk_card_viewer_day} 承担 24h 去重的
 *       持久化真相，批量写用 {@code INSERT ... ON CONFLICT DO NOTHING}。</li>
 *   <li><b>性能放在内存里</b>：进程内去重集合只是优化。它丢了、误判了、被 LRU 驱逐了都不影响最终
 *       计数正确性——这让"重启丢不丢"从正确性问题降级为性能问题。</li>
 * </ul>
 *
 * <p>🔴 服务端<b>不假设前端做过任何去重</b>。前端的"同一卡同一会话只报一次"纯属省流量。</p>
 *
 * <p>丢弃策略是刻意选的方向（§3.10.6）：缓冲区满时丢最旧，且不做本地持久化。丢失曝光的误差方向是
 * "少记额度消耗 → 卡在保底池待更久 → 偏向多给曝光"，与北极星同向；反过来（多记）会系统性少给曝光。</p>
 */
@Slf4j
public final class ExposureRecorder {

    /** 待落库的一条曝光。 */
    public record Row(long cardId, long viewerId, int day, boolean viaBoost,
                      String channel, String pool, long createdAt) {
    }

    /** 一次上报的处置结果（响应只回总数，不回细节——避免给刷量者反馈信号）。 */
    public record Outcome(int accepted, int rejected) {
    }

    /** 拒绝原因枚举（§3.10.4），用于 {@code rank_impression_reject} 埋点。 */
    public static final class Reject {
        public static final String UNKNOWN_REQ = "unknown_req";
        public static final String VIEWER_MISMATCH = "viewer_mismatch";
        public static final String CARD_NOT_DELIVERED = "card_not_delivered";
        public static final String OUT_OF_WINDOW = "out_of_window";
        public static final String IMPLAUSIBLE_DWELL = "implausible_dwell";
        public static final String RATE_LIMITED = "rate_limited";
        /**
         * 规格五类之外的第六类：下发口径不是回忆卡（现阶段 {@code /plaza} 出的是宠物窗口）。
         * 这类曝光记进 {@code t_card_exposure} 会污染权重衰减模型的数据源，故整批拒收。
         */
        public static final String NOT_CARD_FEED = "not_card_feed";
        /**
         * 🔴 第七类：下发面是<b>网格层</b>（瀑布），按 {@code SPEC-feed-surfaces} 概述第 1 条不记 {@code n}。
         *
         * <p>⚠️ <b>这个原因出现在埋点里不是异常，是网格层的正常状态。</b>
         * 与 {@link #NOT_CARD_FEED} 分开成两类，是因为两者<b>解除条件完全不同</b>：
         * 前者等「广场改发回忆卡」，后者等「全屏单卡层实现」。
         * 合成一类的话，广场改发卡之后拒收量不会归零，而没人说得清剩下的是哪一种。</p>
         */
        public static final String GRID_SURFACE = "grid_surface";

        private Reject() {
        }
    }

    /** 曝光的停留时长合理区间（§3.10.4 校验 4）。 */
    private static final long DWELL_MIN_MS = 1000L;
    private static final long DWELL_MAX_MS = 300_000L;

    /** reqId 下发后允许上报的时间窗（§3.10.4 校验 4）。 */
    private static final long REPORT_WINDOW_MS = 30 * 60 * 1000L;

    private final ExposureConfig config;
    private final FeedRequestRegistry registry;
    private final PgDb db;
    private final IDGenerator idGenerator;

    /** 进程内 24h 去重集合（key = cardId:viewerId:day），LRU 上界防无界增长。 */
    private final Map<String, Boolean> seen;

    /** 单 viewer 的分钟级计数（key = viewerId:epochMinute）。 */
    private final Map<String, AtomicLong> rateCounters;

    private final BlockingQueue<Row> pending;
    private final ScheduledExecutorService flusher;

    private final AtomicLong droppedByQueueFull = new AtomicLong();
    private final AtomicLong persisted = new AtomicLong();

    public ExposureRecorder(ExposureConfig config, FeedRequestRegistry registry,
                            PgDb db, IDGenerator idGenerator) {
        this.config = config;
        this.registry = registry;
        this.db = db;
        this.idGenerator = idGenerator;
        this.pending = new ArrayBlockingQueue<>(Math.max(1, config.flushQueueMax()));
        this.seen = boundedLru(Math.max(1, config.flushQueueMax()));
        this.rateCounters = boundedLru(100_000);

        if (db != null && config.enabled()) {
            this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "echo-exposure-flush");
                t.setDaemon(true);
                return t;
            });
            flusher.scheduleWithFixedDelay(this::flushQuietly,
                    config.flushIntervalMs(), config.flushIntervalMs(), TimeUnit.MILLISECONDS);
        } else {
            this.flusher = null;
            // DB 关闭时没有调度器，也没有 t_card_exposure。此时曝光记账整体降级为纯内存、
            // 不落库、进程生命周期内有效（仅联调态）——这一点必须在启动日志里说清。
            log.info("曝光记账降级为纯内存态（{}）：不落 t_card_exposure，仅进程内有效",
                    db == null ? "DB 未开启" : "ECHO_EXPOSURE_ENABLED=false");
        }
    }

    private static <K, V> Map<K, V> boundedLru(int max) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > max;
            }
        });
    }

    /**
     * 处理一批上报。五道校验全部在服务端（客户端上报天然可伪造）。
     *
     * @param reqId    该批曝光所属的 feed 请求 id
     * @param viewerId 从 token 解出的账号（🔴 不取前端传的）
     * @param items    上报条目
     */
    public Outcome record(String reqId, long viewerId, List<Item> items) {
        if (!config.enabled()) {
            return new Outcome(0, items == null ? 0 : items.size());
        }
        if (items == null || items.isEmpty()) {
            return new Outcome(0, 0);
        }

        // 校验 1：reqId 必须是服务端真实下发过的 → 整批丢弃
        FeedRequestRegistry.Snapshot snap = registry.lookup(reqId);
        if (snap == null) {
            reject(Reject.UNKNOWN_REQ, items.size());
            return new Outcome(0, items.size());
        }
        // 校验 2：viewerId 必须与 reqId 的下发对象一致 → 整批丢弃。这是越权信号，应告警
        if (snap.viewerId() != viewerId) {
            log.warn("曝光上报 viewer 不匹配（越权信号）：reqId 下发给 {}，上报者 {}",
                    snap.viewerId(), viewerId);
            reject(Reject.VIEWER_MISMATCH, items.size());
            return new Outcome(0, items.size());
        }
        // 🔴 下发口径必须是回忆卡。t_card_exposure.cardId 指向 t_memory_card.id，而现阶段
        //    GET /plaza 出的是宠物窗口（petId），两套 id 不通。宁可整批拒收也不能把 petId
        //    写进去 —— 那会让权重衰减模型读到一批 join 不上任何卡的行，且报表上看不出来。
        if (!FeedRequestRegistry.KIND_CARD.equals(snap.targetKind())) {
            reject(Reject.NOT_CARD_FEED, items.size());
            return new Outcome(0, items.size());
        }
        // 🔴 n 只在全屏层记（SPEC-feed-surfaces 概述 ①）。网格里被列出、被滚过一律不计：
        //    网格里停留 1000ms 是滑动时顺带发生的，不是「看了」。若网格也记，用户滑一屏
        //    就给十几张卡各记一次，一张卡单次浏览烧掉几十次额度 —— 而制动表三档是按真实
        //    投放次数标定的。失真表现为「内容衰减得比预期快」，且报表上查不出原因。
        if (!FeedRequestRegistry.SURFACE_IMMERSIVE.equals(snap.surface())) {
            reject(Reject.GRID_SURFACE, items.size());
            return new Outcome(0, items.size());
        }

        // 单请求上限：超出部分直接丢（前端契约是 ≤50 条，超出拆多请求）
        List<Item> batch = items.size() > config.batchMax()
                ? items.subList(0, config.batchMax()) : items;
        int rejected = items.size() - batch.size();

        int day = today();
        int accepted = 0;
        for (Item item : batch) {
            // 校验 3（整个防刷设计的支点）：cardId 必须在该 reqId 实际下发的集合里。
            // 有了它，攻击者能做到的上限就是"把服务端本来就发给他的卡多报几次"，而那会被 24h 去重吃掉。
            if (!snap.contains(item.cardId())) {
                reject(Reject.CARD_NOT_DELIVERED, 1);
                rejected++;
                continue;
            }
            // 校验 4：时间窗与停留时长的合理性
            if (item.ts() < snap.deliveredAt()
                    || item.ts() > snap.deliveredAt() + REPORT_WINDOW_MS) {
                reject(Reject.OUT_OF_WINDOW, 1);
                rejected++;
                continue;
            }
            if (item.dwellMs() < DWELL_MIN_MS || item.dwellMs() > DWELL_MAX_MS) {
                reject(Reject.IMPLAUSIBLE_DWELL, 1);
                rejected++;
                continue;
            }
            // 校验 5：单 viewer 每分钟条数上限
            if (!allowByRate(viewerId)) {
                reject(Reject.RATE_LIMITED, 1);
                rejected++;
                continue;
            }

            long cardId = parseCardId(item.cardId());
            if (cardId <= 0) {
                rejected++;
                continue;
            }
            // 24h 去重：内存先挡一层（性能），DB 唯一键兜正确性
            String key = cardId + ":" + viewerId + ":" + day;
            if (seen.putIfAbsent(key, Boolean.TRUE) != null) {
                // 重复上报不算 rejected：它是"已经记过账"，对前端来说是成功的
                accepted++;
                continue;
            }

            // 🔴 viaBoost / channel / pool 一律从服务端快照取，不信前端传的任何归属信息
            Row row = new Row(cardId, viewerId, day,
                    snap.boostIds().contains(item.cardId()),
                    snap.channel(), snap.pool(), System.currentTimeMillis());
            if (!pending.offer(row)) {
                // 缓冲满：丢最旧，腾位给新的
                pending.poll();
                droppedByQueueFull.incrementAndGet();
                pending.offer(row);
            }
            accepted++;
        }
        return new Outcome(accepted, rejected);
    }

    /** 一条上报条目（前端传的字段仅这四个，其余服务端自己定）。 */
    public record Item(String cardId, int pos, long dwellMs, long ts) {
    }

    private boolean allowByRate(long viewerId) {
        String key = viewerId + ":" + (System.currentTimeMillis() / 60_000L);
        AtomicLong counter = rateCounters.computeIfAbsent(key, k -> new AtomicLong());
        return counter.incrementAndGet() <= config.rateLimitPerMinute();
    }

    private static void reject(String reason, int count) {
        // 埋点 rank_impression_reject{reason,count}。分析基座（/collect）未就位前先落日志。
        log.debug("rank_impression_reject reason={} count={}", reason, count);
    }

    private static long parseCardId(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    /** yyyyMMdd，口径同 {@code EchoApi.today()}。 */
    private static int today() {
        LocalDate d = LocalDate.now(ZoneId.systemDefault());
        return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }

    // ------------------------------------------------------------------ 落库

    private void flushQuietly() {
        try {
            flush();
        } catch (Exception e) {
            // 落库失败不影响请求链路；下一轮再试（缓冲已被取走的那批会丢，方向与北极星同向）
            log.warn("曝光批量落库失败", e);
        }
    }

    /** 取出待落库的曝光并批量写入；返回实际写入尝试的条数。可被定时器与测试直接调用。 */
    public int flush() throws SQLException {
        if (db == null) {
            return 0;
        }
        List<Row> batch = new ArrayList<>(config.flushBatchSize());
        pending.drainTo(batch, config.flushBatchSize());
        if (batch.isEmpty()) {
            return 0;
        }
        // 🔴 ON CONFLICT DO NOTHING 让 (cardId, viewerId, day) 唯一键成为幂等与去重的最终真相：
        //    内存去重漏掉的重复在这里被吃掉，重放同一批也不会多记。
        String sql = """
                INSERT INTO "t_card_exposure"
                    ("id","cardId","viewerId","day","viaBoost","channel","pool","createdAt")
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT ("cardId","viewerId","day") DO NOTHING
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Row r : batch) {
                ps.setLong(1, idGenerator.nextId());
                ps.setLong(2, r.cardId());
                ps.setLong(3, r.viewerId());
                ps.setInt(4, r.day());
                ps.setShort(5, (short) (r.viaBoost() ? 1 : 0));
                ps.setString(6, r.channel());
                ps.setString(7, r.pool());
                ps.setLong(8, r.createdAt());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        persisted.addAndGet(batch.size());
        return batch.size();
    }

    /** 待落库条数（供背压观测）。 */
    public int pendingCount() {
        return pending.size();
    }

    public long droppedByQueueFull() {
        return droppedByQueueFull.get();
    }

    public long persistedCount() {
        return persisted.get();
    }

    public void shutdown() {
        if (flusher != null) {
            flusher.shutdown();
            try {
                // 停机前把缓冲里剩下的写完，避免白丢一批
                flushQuietly();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }
}
