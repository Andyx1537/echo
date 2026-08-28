package com.echo.http.store;

import com.echo.http.model.ModerationModels.AuditLog;
import com.echo.http.model.ModerationModels.CardVisibilityLog;
import com.echo.http.model.ModerationModels.HandleCommand;
import com.echo.http.model.ModerationModels.HandleResult;
import com.echo.http.model.ModerationModels.MemoryCard;
import com.echo.http.model.ModerationModels.ModerationSetting;
import com.echo.http.model.ModerationModels.ModerationTicket;
import com.echo.http.model.ModerationModels.Report;

import java.util.List;

/**
 * 审核 / 申诉 / 举报域的存储抽象。
 *
 * <p>两套实现：{@link InMemoryModerationStore}（无 DB 也能跑通闭环、单测无需起库）与
 * {@link PgModerationStore}（PostgreSQL 落库，事务由 {@code PgDb.inTransaction} 承载）。</p>
 *
 * <p>🔴 <b>本接口刻意只暴露两个"写状态"的方法</b>（{@link #handleAtomically}、
 * {@link #appealAtomically}），而不提供 {@code updateCardStatus} 之类的散装写入。
 * 理由是 {@code MOD1 ③} 与 {@code API-CONTRACT §17.0 M-C} 要求「每一次审核状态变更都要落
 * {@code t_card_visibility_log} + {@code t_audit_log} 双流水，且与状态变更同一事务」。
 * 只要接口上存在一个能单独改状态的方法，这条约束就迟早会被绕过；把它收敛成一个方法，
 * 「同一事务」在结构上就是唯一可能的写法。</p>
 */
public interface ModerationStore {

    // ------------------------------------------------------------------ 读

    /** 按 id 取卡；不存在返回 null。 */
    MemoryCard card(long cardId);

    /** 按 id 取审核工单；不存在返回 null。 */
    ModerationTicket ticket(long moderationId);

    /** 取某张卡当前的审核工单（最新一条）；不存在返回 null。 */
    ModerationTicket ticketOfCard(long cardId);

    /**
     * 审核队列。
     *
     * @param tab    pending | blocked | appealing | handled
     * @param risk   low | mid | high，null/空 = 不限
     * @param cursor 上一页末条的 id，0 = 首页
     * @param limit  本页条数上限
     */
    List<ModerationTicket> queue(String tab, String risk, long cursor, int limit);

    /** 某张卡的历次处置流水（§17.1 详情里的 {@code history[]}），按时间升序。 */
    List<CardVisibilityLog> historyOfCard(long cardId);

    /**
     * 广场候选卡：{@code status='public'} 且未软删，按 {@code publishedAt DESC}。
     *
     * <p>🔴 <b>只按状态过滤，不在这一层判可见性。</b>「卡不得宽于窗」是<b>读时取交集</b>
     * （{@code CardVisibility.effective}），而窗的可见性不在本表里——
     * 强行在 SQL 里 join 会让这个约束分散到两处，届时改一处忘一处的后果是内容泄漏。
     * 交集统一由调用方在组装响应前算，只有一个地方。</p>
     *
     * <p>⚠️ 🔴 <b>本方法也不排 {@code pinnedAt}</b>：置顶绝不进共鸣厅公开流
     * （{@code PinPolicy} 类文档），那会和权重衰减正面打架。</p>
     */
    List<MemoryCard> publicCards(int limit);

    /**
     * 某作者的全部未软删卡，按 {@code pinnedAt DESC NULLS LAST, publishedAt DESC}。
     *
     * <p>🔴 <b>不在这一层按状态或可见性过滤</b>：同一个查询要同时服务
     * 「作者看自己的（含草稿、审核中、被打回）」与「陌生人看他的公开卡」两种视图，
     * 裁剪由调用方按访客身份做。⚠️ 在这里就过滤掉的话，作者会看不到自己被打回的卡。</p>
     */
    List<MemoryCard> cardsOfOwner(long ownerId, int limit);

    /** 某作者当前已置顶的卡数（用于上限校验）。 */
    int countPinned(long ownerId);

    /** 举报列表。{@code status} 为 open|handled，null/空 = 不限；{@code cardId} 为 0 = 不限。 */
    List<Report> reports(String status, long cardId, long cursor, int limit);

    /** 先审后发 / 先发后审开关当前值。 */
    ModerationSetting setting();

    // ------------------------------------------------------------------ 写

    /**
     * 🔴 原子处置：卡状态 + {@code reviewedAt}（仅首次） + {@code t_card_visibility_log} +
     * {@code t_audit_log} + 工单状态，<b>同一事务</b>，全成或全不成。
     *
     * <p>并发保护：按 {@code cmd.expectedCardStatus} 做 CAS。若卡的当前状态已不是期望值
     * （另一个审核员抢先处置了），整体不生效并返回 {@code null}，由调用方转成
     * {@code 3412 moderation_state_conflict}。</p>
     *
     * <p>🔴 实现<b>不得</b>把 {@code originType} 写进任何 UPDATE 语句（{@code MOD1 ②}）。</p>
     *
     * @return 处置结果；CAS 未命中返回 null
     */
    HandleResult handleAtomically(HandleCommand cmd);

    /**
     * 🔴 原子申诉：写 {@code appealAt}/{@code appealText} + 卡转 {@code appealing} + 双流水，
     * <b>同一事务</b>。
     *
     * <p>「一生一次」由落库层的 {@code WHERE "appealAt" IS NULL} 保证——不是先查再写
     * （那中间有竞态窗口，两个并发请求都能查到"还没申诉过"），而是让写入本身带上条件，
     * 影响行数为 0 即说明机会已用掉。</p>
     *
     * @return 处置结果；{@code appealAt} 已存在（机会用掉）返回 null
     */
    HandleResult appealAtomically(HandleCommand cmd, String appealText);

    /** 更新先审后发开关，并写一条审计（{@code moderation.settings.update}）。 */
    ModerationSetting updateSetting(String mode, String scopeJson, long operatorId, long now);

    /**
     * 作者更新自己单条内容的互动开关（{@code S3} 关互动能力）。
     *
     * <p>🔴 这个方法<b>只能</b>写 {@code interaction} 与 {@code updatedAt} 两列。它不是
     * {@link #handleAtomically} 的旁路：作者关互动不是审核动作，不改卡状态、不改可见性，
     * 因此不落双流水；但它也<b>绝不能</b>碰 {@code originType}（{@code MOD1 ②}）
     * 或 {@code reviewedAt}（{@code MOD1 ①}）。窄到只有两列就是这条约束的实现方式。</p>
     *
     * <p>⚠️ 不要改用 {@link #putCard}——那个方法是 {@code ON CONFLICT DO NOTHING} 的造数入口，
     * 对已存在的卡是彻底的空操作。</p>
     *
     * @return true 表示确实更新了一行（卡存在）
     */
    boolean updateInteraction(long cardId, String interactionJson, long now);

    /**
     * 🔴 作者改自己卡的可见性：卡表窄 UPDATE + {@code t_card_visibility_log}，<b>同一事务</b>。
     *
     * <p>流水的 {@code changedRole} 取 {@code author}——白名单里本来就有这一档
     * （{@code t_card_visibility_log_ck_role} 含 {@code author}），
     * 数据层与流水口径早已假设作者可自改可见性，缺的一直是端点那一层。</p>
     *
     * <p>并发保护：{@code WHERE "visibility" = ?} 做 CAS，避免两个并发请求各写一条流水
     * 而其中一条的 {@code fromVisibility} 是错的（⚠️ 流水只追加不修改，写错了没法回头改）。</p>
     *
     * <p>🔴 <b>{@code clearPin} 为 true 时同一条语句里把 {@code pinnedAt} 清空</b>，
     * 不是随后再补一次 UPDATE：分两步的话，中间崩掉会留下「已经不公开、但还置顶着」的卡，
     * 而它下次过审时会自己跳回置顶位。</p>
     *
     * @param expectedVisibility CAS 期望的当前可见性
     * @return true 表示确实改了一行；false = 卡不存在 / 不属于该作者 / CAS 未命中
     */
    boolean changeVisibilityAtomically(long cardId, long ownerId, String expectedVisibility,
                                       String toVisibility, boolean clearPin, long now);

    /**
     * 🔴 置顶 / 取消置顶。上限校验与写入在<b>同一事务</b>内完成。
     *
     * <p>⚠️ <b>为什么上限不能先查后写</b>：作者同时点两张卡的置顶，两个请求都查到「已置顶 2 张、
     * 还能再置 1 张」，然后都写进去——结果 4 张。上限是产品承诺，
     * 靠"查一下"守不住，得让计数与写入在一个事务里。</p>
     *
     * @param maxPinned 上限（后台可配，由 {@code PinPolicy} 给出）
     * @return {@link PinOutcome#OK} / {@link PinOutcome#AT_CAPACITY} / {@link PinOutcome#NOT_FOUND}
     */
    PinOutcome setPinnedAtomically(long cardId, long ownerId, boolean pin, int maxPinned, long now);

    /** {@link #setPinnedAtomically} 的结果。 */
    enum PinOutcome {
        /** 置顶/取消成功。 */
        OK,
        /** 🔴 已达上限，未写入。 */
        AT_CAPACITY,
        /** 卡不存在、不属于该作者、已软删，或不满足「public 且已过审」。 */
        NOT_FOUND
    }

    // ------------------------------------------------------------------ 造数（开发环境）

    /** 落一张卡（发布路径的最小替身；🔴 originType 必须由调用方显式给出）。 */
    void putCard(MemoryCard card);

    /** 落一条审核工单。 */
    void putTicket(ModerationTicket ticket);

    /** 落一条举报。 */
    void putReport(Report report);

    /** 读审计账本（🔴 仅供后台与测试断言；C 端无任何路由读取本表）。 */
    List<AuditLog> auditLogs(String targetType, String targetId);
}
