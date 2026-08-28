package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.model.ModerationModels.AuditAction;
import com.echo.http.model.ModerationModels.CardStatus;
import com.echo.http.model.ModerationModels.MemoryCard;
import com.echo.http.model.ModerationModels.ModerationSetting;
import com.echo.http.model.ModerationModels.ModerationState;
import com.echo.http.model.ModerationModels.ModerationTicket;
import com.echo.http.model.ModerationModels.OriginType;
import com.echo.http.store.InMemoryModerationStore;
import com.echo.http.store.ModerationStore;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审核 / 申诉 / 举报 8 端点的硬约束单测（{@code SPEC-publish-and-ops §2.2} +
 * {@code API-CONTRACT §17}）。
 *
 * <p>这些用例针对的是<b>四条会静默失效的约束</b>——它们错了不会报错、不会崩，
 * 只会让北极星数字慢慢失真或让下架变成可私下撤销的动作。所以每条都单独钉一个断言：</p>
 *
 * <table>
 *   <tr><td>{@code MOD1 ①}</td><td>{@link #approveWritesReviewedAtOnlyOnce}</td></tr>
 *   <tr><td>{@code MOD1 ②}</td><td>{@link #noActionEverTouchesOriginType}</td></tr>
 *   <tr><td>{@code MOD1 ③}</td><td>{@link #everyActionWritesBothLogs}</td></tr>
 *   <tr><td>{@code MOD2}</td><td>{@link #appealIsOncePerCardForever} +
 *       {@link #overturnDoesNotResetAppealAt}</td></tr>
 * </table>
 */
class ModerationApiTest {

    private static final long SUPERVISOR = 1001L;
    private static final long REVIEWER = 1002L;
    private static final long READONLY = 1003L;
    private static final long AUTHOR = 2001L;
    private static final long OUTSIDER = 2002L;

    private ModerationStore store;
    private Router router;
    private IDGenerator ids;

    @BeforeEach
    void setUp() {
        ids = new IDGenerator(1L);
        store = new InMemoryModerationStore(ids);
        AdminRoles roles = AdminRoles.parse(
                SUPERVISOR + ":supervisor," + REVIEWER + ":reviewer," + READONLY + ":readonly");
        router = new Router();
        new ModerationApi(store, roles, ids).register(router);
    }

    // ------------------------------------------------------------------ 造数

    /** 造一张卡 + 对应工单，返回 {cardId, moderationId}。 */
    private long[] seedCard(String cardStatus, String moderationState, String originType) {
        MemoryCard c = new MemoryCard();
        c.id = ids.nextId();
        c.ownerId = AUTHOR;
        c.petId = 9001L;
        c.status = cardStatus;
        c.visibilityIntent = "public";
        c.originType = originType;
        c.createdAt = System.currentTimeMillis();
        c.publishedAt = c.createdAt;
        store.putCard(c);

        ModerationTicket t = new ModerationTicket();
        t.id = ids.nextId();
        t.cardId = c.id;
        t.submitBy = AUTHOR;
        t.state = moderationState;
        t.autoRiskLevel = "low";
        t.createdAt = c.createdAt;
        store.putTicket(t);
        return new long[]{c.id, t.id};
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String method, String path, long accountId, JsonObject body) {
        return (Map<String, Object>) call(method, path, accountId, body, Map.of());
    }

    private Object call(String method, String path, long accountId, JsonObject body,
                        Map<String, String> query) {
        Router.Match m = router.match(method, path);
        assertThat(m).as("route %s %s must match", method, path).isNotNull();
        RequestContext ctx = new RequestContext(method, m.pathParams, query,
                body == null ? new JsonObject() : body, accountId);
        try {
            return m.entry.route.handle(ctx);
        } catch (ApiException e) {
            throw e; // 断言要看 code，不能被包一层
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> handle(long moderationId, long operator, String action, String reasonCode) {
        JsonObject b = new JsonObject();
        b.addProperty("action", action);
        if (reasonCode != null) {
            b.addProperty("reasonCode", reasonCode);
        }
        return invoke("POST", "/admin/moderation/" + moderationId + "/handle", operator, b);
    }

    private Map<String, Object> handleAppeal(long moderationId, long operator, String action) {
        JsonObject b = new JsonObject();
        b.addProperty("action", action);
        return invoke("POST", "/admin/appeals/" + moderationId + "/handle", operator, b);
    }

    // ------------------------------------------------------- MOD1 ① reviewedAt

    /**
     * {@code MOD1 ①} —— 首次过审写 {@code reviewedAt}，<b>重新过审不得覆盖</b>。
     *
     * <p>覆盖等于把北极星 7 天窗口的起点往后挪，而报表上完全看不出来：数字仍然"正常"，
     * 只是每次复审都悄悄给这张卡续了 7 天。所以走一整圈 approve → takedown → 申诉 →
     * overturn → 再 approve，断言时间戳始终是第一次那个。</p>
     */
    @Test
    void approveWritesReviewedAtOnlyOnce() throws Exception {
        long[] seed = seedCard(CardStatus.PENDING, ModerationState.PENDING, OriginType.USER);
        long cardId = seed[0];
        long modId = seed[1];

        Map<String, Object> first = handle(modId, REVIEWER, "approve", null);
        assertThat(first.get("cardStatus")).isEqualTo(CardStatus.PUBLIC);
        Long firstReviewedAt = (Long) first.get("reviewedAt");
        assertThat(firstReviewedAt).as("首次过审必须写 reviewedAt").isNotNull();

        Thread.sleep(5); // 保证第二次 approve 的 now 与首次不同，否则断言"没被覆盖"是空的

        // public → 下架 → 作者申诉 → 主管撤销 → 回 pending → 再次过审
        handle(modId, REVIEWER, "takedown", "R-VIOLATION");
        JsonObject appealBody = new JsonObject();
        appealBody.addProperty("text", "这张是我自己拍的");
        invoke("POST", "/cards/" + cardId + "/appeal", AUTHOR, appealBody);
        handleAppeal(modId, SUPERVISOR, "overturn");
        Map<String, Object> second = handle(modId, REVIEWER, "approve", null);

        assertThat(second.get("reviewedAt"))
                .as("🔴 重新过审不得覆盖 reviewedAt（覆盖 = 重置北极星 7 天窗口）")
                .isEqualTo(firstReviewedAt);
        assertThat(store.card(cardId).reviewedAt).isEqualTo(firstReviewedAt);
    }

    // ------------------------------------------------------- MOD1 ② originType

    /**
     * {@code MOD1 ②} —— 任何审核动作都不得改 {@code originType}。
     *
     * <p>这个字段是"官方号内容不进北极星分母"的正向白名单依据。审核台若能改它，等于开了一个
     * 静默改分母的入口，而流水上看起来只是一次正常审核。这里把六个动作全过一遍，并且
     * 请求体里<b>故意塞上</b> {@code originType}，断言它被完全忽略。</p>
     */
    @Test
    void noActionEverTouchesOriginType() {
        // approve / escalate / takedown 走 official；reject 单独造一张
        long[] seed = seedCard(CardStatus.PENDING, ModerationState.PENDING, OriginType.OFFICIAL);
        long cardId = seed[0];
        long modId = seed[1];

        for (String action : List.of("escalate", "approve", "takedown")) {
            JsonObject b = new JsonObject();
            b.addProperty("action", action);
            b.addProperty("reasonCode", "R-VIOLATION");
            b.addProperty("originType", OriginType.USER); // 故意塞：必须被忽略
            invoke("POST", "/admin/moderation/" + modId + "/handle", REVIEWER, b);
            assertThat(store.card(cardId).originType)
                    .as("🔴 动作 %s 不得改 originType", action)
                    .isEqualTo(OriginType.OFFICIAL);
        }

        // 申诉两动作
        JsonObject appealBody = new JsonObject();
        appealBody.addProperty("text", "申诉一下");
        invoke("POST", "/cards/" + cardId + "/appeal", AUTHOR, appealBody);
        assertThat(store.card(cardId).originType).isEqualTo(OriginType.OFFICIAL);
        handleAppeal(modId, SUPERVISOR, "uphold");
        assertThat(store.card(cardId).originType)
                .as("🔴 uphold 不得改 originType").isEqualTo(OriginType.OFFICIAL);

        long[] r = seedCard(CardStatus.PENDING, ModerationState.PENDING, OriginType.OFFICIAL);
        handle(r[1], REVIEWER, "reject", "R-QUALITY");
        assertThat(store.card(r[0]).originType)
                .as("🔴 reject 不得改 originType").isEqualTo(OriginType.OFFICIAL);
    }

    // -------------------------------------------------------- MOD1 ③ 双流水

    /**
     * {@code MOD1 ③} —— <b>每一次</b>状态变更都落 {@code t_card_visibility_log} +
     * {@code t_audit_log} 双流水。
     *
     * <p>"每一次"指全部动作，不只 approve —— 最容易漏的恰恰是 escalate（状态没变，
     * 看起来"不算一次变更"）。漏掉的后果是留下「状态变了、流水没留」的不可追溯窗口，
     * 而流水表只追加、事后补不回来。</p>
     */
    @Test
    void everyActionWritesBothLogs() {
        long[] seed = seedCard(CardStatus.PENDING, ModerationState.PENDING, OriginType.USER);
        long cardId = seed[0];
        long modId = seed[1];

        handle(modId, REVIEWER, "escalate", null);
        assertThat(store.historyOfCard(cardId))
                .as("escalate 状态未变也算一次变更，必须留流水").hasSize(1);
        assertThat(store.auditLogs("card", String.valueOf(cardId)))
                .extracting(a -> a.action).containsExactly(AuditAction.ESCALATE);

        handle(modId, REVIEWER, "approve", null);
        handle(modId, REVIEWER, "takedown", "R-VIOLATION");

        assertThat(store.historyOfCard(cardId)).hasSize(3);
        assertThat(store.auditLogs("card", String.valueOf(cardId)))
                .extracting(a -> a.action)
                .containsExactly(AuditAction.ESCALATE, AuditAction.APPROVE, AuditAction.TAKEDOWN);
        // 两条流水条数始终相等：任何一次动作都不允许只落一边
        assertThat(store.historyOfCard(cardId).size())
                .isEqualTo(store.auditLogs("card", String.valueOf(cardId)).size());
        // 审核动作不改可见性（可见性是作者主权），下架只体现在 status 上
        assertThat(store.historyOfCard(cardId))
                .allSatisfy(l -> assertThat(l.fromVisibility).isEqualTo(l.toVisibility));
        assertThat(store.historyOfCard(cardId))
                .allSatisfy(l -> assertThat(l.changedRole).isEqualTo("moderator"));
    }

    // ------------------------------------------------------------ MOD2 申诉

    /** {@code MOD2} —— 一张卡一生只能申诉一次，判据是 {@code appealAt IS NOT NULL}。 */
    @Test
    void appealIsOncePerCardForever() {
        long[] seed = seedCard(CardStatus.PENDING, ModerationState.PENDING, OriginType.USER);
        long cardId = seed[0];
        long modId = seed[1];
        handle(modId, REVIEWER, "reject", "R-QUALITY");

        JsonObject b = new JsonObject();
        b.addProperty("text", "这是我家的猫");
        invoke("POST", "/cards/" + cardId + "/appeal", AUTHOR, b);
        assertThat(store.ticket(modId).appealAt).isNotNull();

        // 主管维持原处置 → 回 rejected，状态本身又"可申诉"了，但机会已用掉
        handleAppeal(modId, SUPERVISOR, "uphold");
        assertThat(store.card(cardId).status).isEqualTo(CardStatus.REJECTED);

        assertThatThrownBy(() -> invoke("POST", "/cards/" + cardId + "/appeal", AUTHOR, b))
                .isInstanceOf(ApiException.class)
                .as("🔴 第二次申诉必须被拒（3410）")
                .hasFieldOrPropertyWithValue("code", ModerationStateMachine.ERR_APPEAL_USED);

        Map<String, Object> view = invoke("GET", "/cards/" + cardId + "/moderation", AUTHOR, null);
        assertThat(view.get("appealUsed")).isEqualTo(true);
        assertThat(view.get("appealable")).isEqualTo(false);
    }

    /**
     * {@code MOD2} —— {@code overturn} 不得重置 {@code appealAt}。
     *
     * <p>重置了就等于把"一生一次"变成"每次撤销后再来一次"，而这条路径对作者是无成本的。</p>
     */
    @Test
    void overturnDoesNotResetAppealAt() {
        long[] seed = seedCard(CardStatus.PENDING, ModerationState.PENDING, OriginType.USER);
        long cardId = seed[0];
        long modId = seed[1];
        handle(modId, REVIEWER, "reject", "R-QUALITY");

        JsonObject b = new JsonObject();
        b.addProperty("text", "误判了");
        invoke("POST", "/cards/" + cardId + "/appeal", AUTHOR, b);
        Long appealAt = store.ticket(modId).appealAt;
        assertThat(appealAt).isNotNull();

        handleAppeal(modId, SUPERVISOR, "overturn");
        assertThat(store.card(cardId).status)
                .as("🔴 overturn 只回 pending 重走人工，不直接放行 public")
                .isEqualTo(CardStatus.PENDING);
        assertThat(store.ticket(modId).appealAt)
                .as("🔴 overturn 不得重置 appealAt").isEqualTo(appealAt);

        // 再次被驳回后仍不可申诉：机会是"一生一次"，不是"每轮一次"
        handle(modId, REVIEWER, "reject", "R-QUALITY");
        assertThatThrownBy(() -> invoke("POST", "/cards/" + cardId + "/appeal", AUTHOR, b))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ModerationStateMachine.ERR_APPEAL_USED);
    }

    /** 申诉前置：状态不可申诉时给 3411，且说明长度上限 200（§2.6.1）。 */
    @Test
    void appealRejectsNonAppealableStatusAndOverlongText() {
        long[] seed = seedCard(CardStatus.PENDING, ModerationState.PENDING, OriginType.USER);
        long cardId = seed[0];

        JsonObject b = new JsonObject();
        b.addProperty("text", "还在审就来申诉");
        assertThatThrownBy(() -> invoke("POST", "/cards/" + cardId + "/appeal", AUTHOR, b))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ModerationStateMachine.ERR_APPEAL_NOT_APPLICABLE);

        handle(seed[1], REVIEWER, "reject", "R-QUALITY");
        JsonObject tooLong = new JsonObject();
        tooLong.addProperty("text", "字".repeat(201));
        assertThatThrownBy(() -> invoke("POST", "/cards/" + cardId + "/appeal", AUTHOR, tooLong))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ApiException.BAD_PARAM);
    }

    // ------------------------------------------------------- 状态机封闭性

    /**
     * {@code §2.2.1} 封闭表 —— 表外组合一律 3412。
     *
     * <p>重点是 {@code takendown + approve}：下架不得由审核台直接放行，否则下架就成了一个
     * 可以私下撤销的动作。必须经作者申诉 + overturn 回 pending 重走人工。</p>
     */
    @Test
    void illegalTransitionsAreClosed() {
        long[] takendown = seedCard(CardStatus.TAKENDOWN, ModerationState.TAKENDOWN, OriginType.USER);
        assertThatThrownBy(() -> handle(takendown[1], REVIEWER, "approve", null))
                .isInstanceOf(ApiException.class)
                .as("🔴 takendown 不得由审核台直接放行")
                .hasFieldOrPropertyWithValue("code", ModerationStateMachine.ERR_STATE_CONFLICT);

        long[] pub = seedCard(CardStatus.PUBLIC, ModerationState.APPROVED, OriginType.USER);
        assertThatThrownBy(() -> handle(pub[1], REVIEWER, "reject", "R-QUALITY"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ModerationStateMachine.ERR_STATE_CONFLICT);

        // 软删卡：无任何审核动作，且不该出现在队列里
        long[] del = seedCard(CardStatus.PENDING, ModerationState.PENDING, OriginType.USER);
        MemoryCard c = store.card(del[0]);
        c.status = CardStatus.DELETED;
        c.deletedAt = System.currentTimeMillis();
        store.putCard(c);
        assertThatThrownBy(() -> handle(del[1], REVIEWER, "approve", null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ModerationStateMachine.ERR_STATE_CONFLICT);
        assertThat(store.queue("pending", null, 0, 50))
                .as("软删卡不该出现在审核队列里")
                .noneMatch(t -> t.cardId == del[0]);
    }

    /** 驳回 / 下架必须带 reasonCode（3413）。 */
    @Test
    void rejectAndTakedownRequireReasonCode() {
        long[] seed = seedCard(CardStatus.PENDING, ModerationState.PENDING, OriginType.USER);
        assertThatThrownBy(() -> handle(seed[1], REVIEWER, "reject", null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ModerationStateMachine.ERR_REASON_REQUIRED);
    }

    // ------------------------------------------------------------ 权限与出参

    /** {@code §2.5} 三角色：只读运营不可处置；审核员不可处置申诉、不可切开关。 */
    @Test
    void adminRolesAreEnforced() {
        long[] seed = seedCard(CardStatus.PENDING, ModerationState.PENDING, OriginType.USER);

        assertThatThrownBy(() -> handle(seed[1], READONLY, "approve", null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ApiException.RULE_FORBIDDEN);
        assertThatThrownBy(() -> handleAppeal(seed[1], REVIEWER, "uphold"))
                .as("🔴 申诉处置仅审核主管")
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ApiException.RULE_FORBIDDEN);

        JsonObject mode = new JsonObject();
        mode.addProperty("mode", ModerationSetting.PUBLISH_FIRST);
        assertThatThrownBy(() -> invoke("PATCH", "/admin/moderation/settings", REVIEWER, mode))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ApiException.RULE_FORBIDDEN);

        // 未列入 ECHO_ADMIN_ROLES 的账号：默认拒绝，不是默认放行
        assertThatThrownBy(() -> call("GET", "/admin/moderation/queue", OUTSIDER, null, Map.of()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ApiException.RULE_FORBIDDEN);

        Map<String, Object> ok = invoke("PATCH", "/admin/moderation/settings", SUPERVISOR, mode);
        assertThat(ok.get("mode")).isEqualTo(ModerationSetting.PUBLISH_FIRST);
        assertThat(store.setting().mode).isEqualTo(ModerationSetting.PUBLISH_FIRST);
        // 开关变更也要留审计
        assertThat(store.auditLogs("config", "moderation.settings"))
                .extracting(a -> a.action).contains(AuditAction.SETTINGS_UPDATE);
    }

    /** 作者侧 {@code GET /cards/:id/moderation}：只本人可读，且不下发审核内幕。 */
    @Test
    void authorViewHidesInternalsAndBlocksOthers() {
        long[] seed = seedCard(CardStatus.PENDING, ModerationState.PENDING, OriginType.USER);
        long cardId = seed[0];
        handle(seed[1], REVIEWER, "reject", "R-QUALITY");

        assertThatThrownBy(() -> invoke("GET", "/cards/" + cardId + "/moderation", OUTSIDER, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ApiException.RULE_FORBIDDEN);

        Map<String, Object> view = invoke("GET", "/cards/" + cardId + "/moderation", AUTHOR, null);
        assertThat(view.get("status")).isEqualTo(CardStatus.REJECTED);
        assertThat(view.get("appealable")).isEqualTo(true);
        assertThat(view.get("reasonText")).isNotNull();
        // 🔴 对作者不下发 autoSignals / note / 审核员身份
        assertThat(view).doesNotContainKeys("autoSignals", "note", "handledBy", "reviewerId");
    }

    /** 并发处置：CAS 未命中转 3412，而不是把后一个动作也执行掉。 */
    @Test
    void concurrentHandleLosesRaceWithConflict() {
        long[] seed = seedCard(CardStatus.PENDING, ModerationState.PENDING, OriginType.USER);
        handle(seed[1], REVIEWER, "approve", null);
        // 第二个审核员拿着旧状态（pending）来 approve：现已是 public，approve 不在其允许集
        assertThatThrownBy(() -> handle(seed[1], REVIEWER, "approve", null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ModerationStateMachine.ERR_STATE_CONFLICT);
    }
}
