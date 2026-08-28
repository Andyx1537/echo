package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.model.ModerationModels.Action;
import com.echo.http.model.ModerationModels.AuditAction;
import com.echo.http.model.ModerationModels.CardStatus;
import com.echo.http.model.ModerationModels.CardVisibilityLog;
import com.echo.http.model.ModerationModels.HandleCommand;
import com.echo.http.model.ModerationModels.HandleResult;
import com.echo.http.model.ModerationModels.MemoryCard;
import com.echo.http.model.ModerationModels.ModerationSetting;
import com.echo.http.model.ModerationModels.ModerationState;
import com.echo.http.model.ModerationModels.ModerationTicket;
import com.echo.http.model.ModerationModels.Report;
import com.echo.http.store.ModerationStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 审核 / 申诉 / 举报的 8 个端点（{@code API-CONTRACT §17}：运营侧 6 条 + 作者侧 2 条）。
 *
 * <p>流程与状态机真源为 {@code SPEC-publish-and-ops §2.2/§2.2.1/§2.2.2}，端点契约真源为
 * {@code API-CONTRACT §17}。四条硬约束的落点：</p>
 *
 * <table>
 *   <tr><td>{@code MOD1 ①}</td><td>{@code approve} 同事务写 {@code reviewedAt} 且只写一次 →
 *       {@link ModerationStateMachine#writesReviewedAt} 只对 approve 返 true，落库层用
 *       {@code COALESCE("reviewedAt", ?)} 保证只写一次</td></tr>
 *   <tr><td>{@code MOD1 ②}</td><td>不得写改 {@code originType} → {@link HandleCommand} 没有这个字段，
 *       且 {@code PgModerationStore} 在类加载时断言卡表 UPDATE 不含该列</td></tr>
 *   <tr><td>{@code MOD1 ③}</td><td>双流水同事务 → store 只暴露
 *       {@code handleAtomically}/{@code appealAtomically}，没有能单独改状态的方法</td></tr>
 *   <tr><td>{@code MOD2}</td><td>申诉一生一次 → 判据是 {@code appealAt IS NOT NULL}，
 *       写入带 {@code WHERE "appealAt" IS NULL}；{@code overturn} 不碰它</td></tr>
 * </table>
 */
@Slf4j
public final class ModerationApi {

    /** 队列/列表默认与最大页长。 */
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    /** SLA：人工队列 ≤4h、高风险 ≤1h（§2.4）。由服务端算，前端不自算。 */
    private static final long SLA_NORMAL_MS = 4 * 60 * 60 * 1000L;
    private static final long SLA_HIGH_MS = 60 * 60 * 1000L;

    /** 申诉说明长度上限（§2.6.1）。 */
    private static final int APPEAL_TEXT_MAX = 200;

    private final ModerationStore store;
    private final AdminRoles adminRoles;
    private final IDGenerator idGenerator;

    public ModerationApi(ModerationStore store, AdminRoles adminRoles, IDGenerator idGenerator) {
        this.store = store;
        this.adminRoles = adminRoles;
        this.idGenerator = idGenerator;
    }

    /** 把 8 条路由挂到 router 上（base path {@code /api/v1} 由网关剥离）。 */
    public void register(Router r) {
        // 运营侧 6 条（/admin/**，独立鉴权链）
        r.add("GET", "/admin/moderation/queue", this::queue);
        r.add("GET", "/admin/moderation/:id", this::detail);
        r.add("POST", "/admin/moderation/:id/handle", this::handle);
        r.add("POST", "/admin/appeals/:id/handle", this::handleAppeal);
        r.add("GET", "/admin/reports", this::reports);
        r.add("PATCH", "/admin/moderation/settings", this::updateSettings);
        // 作者侧 2 条（C 端）
        r.add("GET", "/cards/:id/moderation", this::myCardModeration);
        r.add("POST", "/cards/:id/appeal", this::appeal);
    }

    // ================================================================ 运营侧

    /** {@code GET /admin/moderation/queue} —— 四 tab 队列，待处理 + 高风险优先。 */
    private Object queue(RequestContext ctx) {
        adminRoles.requireRead(ctx.accountId());
        String tab = ctx.query("tab", "pending");
        String risk = ctx.query("risk", null);
        long cursor = parseLong(ctx.query("cursor", "0"), 0L);
        int limit = clampLimit(ctx.queryInt("limit", DEFAULT_LIMIT));

        List<ModerationTicket> tickets = store.queue(tab, risk, cursor, limit);
        List<Object> items = new ArrayList<>(tickets.size());
        for (ModerationTicket t : tickets) {
            items.add(queueItem(t, store.card(t.cardId)));
        }
        return envelope(items, tickets.size() < limit || tickets.isEmpty()
                ? null : String.valueOf(tickets.get(tickets.size() - 1).id));
    }

    /** {@code GET /admin/moderation/:id} —— 单条详情（含历次处置流水）。 */
    private Object detail(RequestContext ctx) {
        adminRoles.requireRead(ctx.accountId());
        ModerationTicket t = requireTicket(parseLong(ctx.path("id"), 0L));
        MemoryCard c = store.card(t.cardId);

        Map<String, Object> data = queueItem(t, c);
        data.put("note", t.note);
        data.put("handledBy", t.handledBy == null ? null : String.valueOf(t.handledBy));
        data.put("handledAt", t.handledAt);
        data.put("reasonCode", t.reasonCode);
        data.put("appeal", appealBlock(t));

        List<Object> history = new ArrayList<>();
        for (CardVisibilityLog l : store.historyOfCard(t.cardId)) {
            Map<String, Object> h = Json.map();
            h.put("fromVisibility", l.fromVisibility);
            h.put("toVisibility", l.toVisibility);
            h.put("fromStatus", l.fromStatus);
            h.put("toStatus", l.toStatus);
            h.put("changedBy", String.valueOf(l.changedBy));
            h.put("changedRole", l.changedRole);
            h.put("reasonCode", l.reasonCode);
            h.put("changedAt", l.changedAt);
            history.add(h);
        }
        data.put("history", history);
        return data;
    }

    /** {@code POST /admin/moderation/:id/handle} —— 人工四动作：通过 / 驳回 / 下架 / 升级复核。 */
    private Object handle(RequestContext ctx) {
        adminRoles.requireHandle(ctx.accountId());
        long moderationId = parseLong(ctx.path("id"), 0L);
        String action = Json.requireString(ctx.body(), "action");
        if (!ModerationStateMachine.isModeratorAction(action)) {
            throw new ApiException(ApiException.BAD_PARAM,
                    "这个动作暂时用不了，换个方式试试？", "unknown action: " + action);
        }
        String reasonCode = Json.getString(ctx.body(), "reasonCode", null);
        String note = Json.getString(ctx.body(), "note", null);
        if (ModerationStateMachine.requiresReasonCode(action) && isBlank(reasonCode)) {
            throw new ApiException(ModerationStateMachine.ERR_REASON_REQUIRED,
                    "还差一个处置理由，选一个就好。", "reason_code_required");
        }
        return applyModeratorAction(moderationId, action, reasonCode, note, ctx.accountId());
    }

    /** {@code POST /admin/appeals/:id/handle} —— 申诉处置（🔴 仅审核主管）。 */
    private Object handleAppeal(RequestContext ctx) {
        adminRoles.requireSupervisor(ctx.accountId());
        long moderationId = parseLong(ctx.path("id"), 0L);
        String action = Json.requireString(ctx.body(), "action");
        if (!ModerationStateMachine.isAppealAction(action)) {
            throw new ApiException(ApiException.BAD_PARAM,
                    "这个动作暂时用不了，换个方式试试？", "unknown appeal action: " + action);
        }
        String reasonCode = Json.getString(ctx.body(), "reasonCode", null);
        String note = Json.getString(ctx.body(), "note", null);
        return applyModeratorAction(moderationId, action, reasonCode, note, ctx.accountId());
    }

    /**
     * 五个"运营改状态"的动作走同一条路径：校验迁移合法性 → 组指令 → 交 store 原子提交。
     *
     * <p>合并成一条是刻意的：{@code MOD1 ③} 的「每一次」指全部动作而不只 {@code approve}。
     * 每个动作各写一段落库逻辑，早晚会有一段漏掉流水。</p>
     */
    private Object applyModeratorAction(long moderationId, String action,
                                        String reasonCode, String note, long operatorId) {
        ModerationTicket t = requireTicket(moderationId);
        MemoryCard c = store.card(t.cardId);
        if (c == null) {
            throw new ApiException(ApiException.NOT_FOUND, "这里还空着，没找到你要的内容。",
                    "card not found: " + t.cardId);
        }
        // 🔴 软删卡无任何审核动作，且根本不该出现在队列里（§2.2.1 最后一行）
        if (c.deletedAt != null || CardStatus.DELETED.equals(c.status)) {
            throw stateConflict(action, c.status);
        }
        if (!ModerationStateMachine.allows(c.status, action)) {
            throw stateConflict(action, c.status);
        }

        // uphold 要回到申诉前的原状态：从可见性流水里取那次 rejected/takendown → appealing 的 fromStatus。
        // §17.1 已把 history[] 定义为取自 t_card_visibility_log，这里用的是同一份事实。
        String preAppealStatus = Action.UPHOLD.equals(action) ? preAppealStatus(t.cardId) : null;
        if (Action.UPHOLD.equals(action) && preAppealStatus == null) {
            throw new ApiException(ApiException.SERVER_ERROR,
                    "这里出了点小状况，待会儿再来看看它好吗？",
                    "cannot resolve pre-appeal status for card " + t.cardId);
        }

        HandleCommand cmd = new HandleCommand();
        cmd.moderationId = t.id;
        cmd.cardId = c.id;
        cmd.expectedCardStatus = c.status;
        cmd.toCardStatus = ModerationStateMachine.targetCardStatus(c.status, action, preAppealStatus);
        cmd.toModerationState = ModerationStateMachine.targetModerationState(t.state, action, preAppealStatus);
        // 🔴 审核动作不改可见性：可见性是作者的主权。只记 visibility 分不出「作者撤回」与
        //    「运营下架」，所以流水里两者都记 —— 下架体现在 status 上，visibility 保持原值。
        cmd.fromVisibility = c.visibilityIntent;
        cmd.toVisibility = c.visibilityIntent;
        cmd.operatorId = operatorId;
        cmd.changedRole = "moderator";
        cmd.reasonCode = reasonCode;
        cmd.note = note;
        cmd.auditAction = ModerationStateMachine.auditAction(action);
        cmd.auditActorType = "staff";
        cmd.snapshotJson = snapshot(c);
        cmd.writeReviewedAt = ModerationStateMachine.writesReviewedAt(action);
        cmd.appealResult = ModerationStateMachine.isAppealAction(action) ? action : null;
        cmd.now = System.currentTimeMillis();
        cmd.auditScopeJson = auditScope(t, c.status, cmd.toCardStatus, reasonCode);

        HandleResult result = store.handleAtomically(cmd);
        if (result == null) {
            // CAS 未命中：另一个审核员抢先处置了，当前状态已不是刚才读到的那个
            throw stateConflict(action, c.status);
        }

        Map<String, Object> data = Json.map();
        data.put("moderationId", String.valueOf(result.moderationId));
        data.put("cardId", String.valueOf(result.cardId));
        data.put("state", result.state);
        data.put("cardStatus", result.cardStatus);
        // 出参回显 reviewedAt，便于 QA 直接断言"只写一次"（TC-MOD-06）
        data.put("reviewedAt", result.reviewedAt);
        data.put("handledAt", result.handledAt);
        if (ModerationStateMachine.isAppealAction(action)) {
            data.put("appealId", String.valueOf(result.moderationId));
        }
        return data;
    }

    /** {@code GET /admin/reports} —— 举报列表（支撑 §4 举报率与人工核查）。 */
    private Object reports(RequestContext ctx) {
        adminRoles.requireRead(ctx.accountId());
        String status = ctx.query("status", null);
        long cardId = parseLong(ctx.query("cardId", "0"), 0L);
        long cursor = parseLong(ctx.query("cursor", "0"), 0L);
        int limit = clampLimit(ctx.queryInt("limit", DEFAULT_LIMIT));

        List<Report> rows = store.reports(status, cardId, cursor, limit);
        List<Object> items = new ArrayList<>(rows.size());
        for (Report r : rows) {
            Map<String, Object> m = Json.map();
            m.put("reportId", String.valueOf(r.id));
            m.put("cardId", String.valueOf(r.cardId));
            m.put("reporterId", String.valueOf(r.reporterId));
            m.put("reasonCode", r.reasonCode);
            m.put("note", r.note);
            m.put("status", r.status);
            m.put("createdAt", r.createdAt);
            m.put("handledAt", r.handledAt);
            items.add(m);
        }
        return envelope(items, rows.size() < limit || rows.isEmpty()
                ? null : String.valueOf(rows.get(rows.size() - 1).id));
    }

    /** {@code PATCH /admin/moderation/settings} —— 先审后发 / 先发后审开关（TC-MOD-05）。 */
    private Object updateSettings(RequestContext ctx) {
        adminRoles.requireSupervisor(ctx.accountId());
        String mode = Json.requireString(ctx.body(), "mode");
        if (!ModerationSetting.REVIEW_FIRST.equals(mode) && !ModerationSetting.PUBLISH_FIRST.equals(mode)) {
            throw new ApiException(ApiException.BAD_PARAM,
                    "这个设置我没读懂，换个方式再试试？", "unknown mode: " + mode);
        }
        String scopeJson = ctx.body().has("scope") && !ctx.body().get("scope").isJsonNull()
                ? ctx.body().get("scope").toString() : null;
        ModerationSetting s = store.updateSetting(mode, scopeJson, ctx.accountId(),
                System.currentTimeMillis());

        Map<String, Object> data = Json.map();
        data.put("mode", s.mode);
        data.put("updatedAt", s.updatedAt);
        data.put("updatedBy", String.valueOf(s.updatedBy));
        return data;
    }

    // ================================================================ 作者侧

    /** {@code GET /cards/:id/moderation} —— 我的卡的审核状态（TC-MOD-03 依赖它）。 */
    private Object myCardModeration(RequestContext ctx) {
        long cardId = parseLong(ctx.path("id"), 0L);
        MemoryCard c = store.card(cardId);
        if (c == null || c.deletedAt != null || CardStatus.DELETED.equals(c.status)) {
            // 软删后详情统一 404：对外不暴露"这里曾经有过东西"（§15.1）
            throw new ApiException(ApiException.NOT_FOUND, "这里还空着，没找到你要的内容。",
                    "card not found: " + cardId);
        }
        if (c.ownerId != ctx.accountId()) {
            throw new ApiException(ApiException.RULE_FORBIDDEN, "这是别人的回忆，我们不看。",
                    "not card owner");
        }
        ModerationTicket t = store.ticketOfCard(cardId);

        Map<String, Object> data = Json.map();
        data.put("cardId", String.valueOf(c.id));
        data.put("status", c.status);
        data.put("reasonCode", t == null ? null : t.reasonCode);
        // 🔴 对作者只下发 reasonCode 对应的温柔文案；不下发 autoSignals / note / 审核员身份
        data.put("reasonText", reasonText(t == null ? null : t.reasonCode));
        boolean appealUsed = t != null && t.appealUsed();
        data.put("appealable", ModerationStateMachine.appealable(c.status) && !appealUsed);
        // 🔴 唯一判据 = t_moderation.appealAt IS NOT NULL（MOD2），不由任何计数列推导
        data.put("appealUsed", appealUsed);
        data.put("appeal", appealBlock(t));
        data.put("reviewedAt", c.reviewedAt);
        data.put("handledAt", t == null ? null : t.handledAt);
        return data;
    }

    /** {@code POST /cards/:id/appeal} —— 申诉（🔴 一张卡一生只有一次）。 */
    private Object appeal(RequestContext ctx) {
        long cardId = parseLong(ctx.path("id"), 0L);
        MemoryCard c = store.card(cardId);
        if (c == null || c.deletedAt != null || CardStatus.DELETED.equals(c.status)) {
            throw new ApiException(ApiException.NOT_FOUND, "这里还空着，没找到你要的内容。",
                    "card not found: " + cardId);
        }
        if (c.ownerId != ctx.accountId()) {
            throw new ApiException(ApiException.RULE_FORBIDDEN, "这是别人的回忆，我们不看。",
                    "not card owner");
        }
        String text = Json.requireString(ctx.body(), "text");
        if (text.length() > APPEAL_TEXT_MAX) {
            throw new ApiException(ApiException.BAD_PARAM,
                    "说得有点长了，缩短一些再试试？", "appeal text exceeds " + APPEAL_TEXT_MAX);
        }
        // 入库前过《温柔词表》，同 TC-CARD-06 口径
        String safeText = CopyGuardFilter.sanitize(text);

        if (!ModerationStateMachine.appealable(c.status)) {
            throw new ApiException(ModerationStateMachine.ERR_APPEAL_NOT_APPLICABLE,
                    "这张卡现在还不需要申诉。", "appeal_not_applicable");
        }
        ModerationTicket t = store.ticketOfCard(cardId);
        if (t == null) {
            throw new ApiException(ApiException.NOT_FOUND, "这里还空着，没找到你要的内容。",
                    "no moderation ticket for card " + cardId);
        }
        if (t.appealUsed()) {
            throw appealAlreadyUsed();
        }

        HandleCommand cmd = new HandleCommand();
        cmd.moderationId = t.id;
        cmd.cardId = c.id;
        cmd.expectedCardStatus = c.status;
        cmd.toCardStatus = CardStatus.APPEALING;
        cmd.toModerationState = ModerationState.APPEALING;
        cmd.fromVisibility = c.visibilityIntent;
        cmd.toVisibility = c.visibilityIntent;
        cmd.operatorId = ctx.accountId();
        // 作者发起 → 流水角色是 author（不是 moderator）
        cmd.changedRole = "author";
        cmd.auditAction = AuditAction.APPEAL;
        cmd.auditActorType = "user";
        cmd.snapshotJson = snapshot(c);
        cmd.writeReviewedAt = false;
        cmd.now = System.currentTimeMillis();
        cmd.auditScopeJson = auditScope(t, c.status, CardStatus.APPEALING, null);

        HandleResult result = store.appealAtomically(cmd, safeText);
        if (result == null) {
            // 落库层的 WHERE "appealAt" IS NULL 没命中 = 机会已用掉（并发下的第二次请求也走这里）
            throw appealAlreadyUsed();
        }

        Map<String, Object> data = Json.map();
        data.put("appealId", String.valueOf(result.moderationId));
        data.put("state", CardStatus.APPEALING);
        data.put("createdAt", result.handledAt);
        return data;
    }

    // ================================================================ 辅助

    private Map<String, Object> queueItem(ModerationTicket t, MemoryCard c) {
        Map<String, Object> m = Json.map();
        m.put("moderationId", String.valueOf(t.id));
        m.put("cardId", String.valueOf(t.cardId));
        m.put("submitBy", String.valueOf(t.submitBy));
        m.put("state", t.state);
        m.put("autoRiskLevel", t.autoRiskLevel);
        m.put("autoSignals", t.autoSignalsJson);
        if (c != null) {
            Map<String, Object> snap = Json.map();
            snap.put("title", c.title);
            snap.put("body", c.body);
            snap.put("coverUrl", c.coverKey);
            snap.put("topicIds", c.topicIdsJson);
            m.put("cardSnapshot", snap);
            m.put("cardStatus", c.status);
            // 🔴 originType 只读下发，供审核员知晓这是官方号内容；不提供修改入口（MOD1 ②）
            m.put("originType", c.originType);
            m.put("assistedByOps", c.assistedByOps);
        }
        m.put("createdAt", t.createdAt);
        long slaDueAt = t.createdAt + ("high".equals(t.autoRiskLevel) ? SLA_HIGH_MS : SLA_NORMAL_MS);
        m.put("slaDueAt", slaDueAt);
        // SLA 由服务端判，前端不算时间差
        m.put("slaBreached", t.handledAt == null && System.currentTimeMillis() > slaDueAt);
        return m;
    }

    private Map<String, Object> appealBlock(ModerationTicket t) {
        if (t == null || t.appealAt == null) {
            return null;
        }
        Map<String, Object> a = Json.map();
        a.put("appealId", String.valueOf(t.id));
        a.put("text", t.appealText);
        a.put("appealAt", t.appealAt);
        a.put("result", t.appealResult);
        a.put("handledAt", t.appealHandledAt);
        return a;
    }

    /** 从可见性流水里取"这张卡进 appealing 之前是什么状态"。 */
    private String preAppealStatus(long cardId) {
        List<CardVisibilityLog> history = store.historyOfCard(cardId);
        for (int i = history.size() - 1; i >= 0; i--) {
            CardVisibilityLog l = history.get(i);
            if (CardStatus.APPEALING.equals(l.toStatus)) {
                return l.fromStatus;
            }
        }
        return null;
    }

    /** 处置时的卡面快照（§2.4 留痕四要素之一：谁 / 何时 / 动作 / 理由码 / 快照）。 */
    private static String snapshot(MemoryCard c) {
        Map<String, Object> snap = Json.map();
        snap.put("title", c.title);
        snap.put("body", c.body);
        snap.put("coverKey", c.coverKey);
        snap.put("visibility", c.visibilityIntent);
        snap.put("status", c.status);
        return Json.toJson(snap);
    }

    /** {@code t_audit_log.scope}：便于事后还原一次处置的全貌（§17.5）。 */
    private static String auditScope(ModerationTicket t, String fromStatus, String toStatus,
                                     String reasonCode) {
        Map<String, Object> scope = Json.map();
        scope.put("moderationId", String.valueOf(t.id));
        scope.put("fromStatus", fromStatus);
        scope.put("toStatus", toStatus);
        scope.put("reasonCode", reasonCode);
        scope.put("autoRiskLevel", t.autoRiskLevel);
        return Json.toJson(scope);
    }

    /**
     * 理由码 → 给作者看的温柔文案。
     *
     * <p>⚠️ 理由码字典（取值集合与对应文案）在任何现行规格里都没有定义，
     * {@code API-CONTRACT §17.6} 明确"不代拟、留待派单"（要过 COPY-GUIDE，又牵涉合规分类）。
     * 这里只做一层兜底：有码但字典缺失时给一句克制的通用文案，不裸暴露理由码给作者。</p>
     */
    private static String reasonText(String reasonCode) {
        if (isBlank(reasonCode)) {
            return null;
        }
        return CopyGuardFilter.sanitize("这一条我们看过了，暂时还不能公开。你可以改一改再试试。");
    }

    private ModerationTicket requireTicket(long moderationId) {
        ModerationTicket t = store.ticket(moderationId);
        if (t == null) {
            throw new ApiException(ApiException.NOT_FOUND, "这里还空着，没找到你要的内容。",
                    "moderation not found: " + moderationId);
        }
        return t;
    }

    private static ApiException stateConflict(String action, String currentStatus) {
        return new ApiException(ModerationStateMachine.ERR_STATE_CONFLICT,
                "这张卡的状态已经变了，刷新看看？",
                "moderation_state_conflict: action=" + action + ", status=" + currentStatus);
    }

    private static ApiException appealAlreadyUsed() {
        return new ApiException(ModerationStateMachine.ERR_APPEAL_USED,
                "这张卡已经申诉过一次了，我们会认真看的。", "appeal_already_used");
    }

    private static Map<String, Object> envelope(List<Object> items, String nextCursor) {
        Map<String, Object> data = Json.map();
        data.put("items", items);
        data.put("nextCursor", nextCursor);
        return data;
    }

    private static int clampLimit(int limit) {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 供 dev/测试造数：暴露 store 与发号器。 */
    public ModerationStore store() {
        return store;
    }

    public IDGenerator idGenerator() {
        return idGenerator;
    }
}
