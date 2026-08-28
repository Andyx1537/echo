package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.governance.BlockService;
import com.echo.http.governance.BlockStore;
import com.echo.http.governance.CapabilityRegistry;
import com.echo.http.governance.FeatureSwitchService;
import com.echo.http.governance.FeatureSwitchStore;
import com.echo.http.governance.GovernanceCapability;
import com.echo.http.governance.InteractionPolicy;
import com.echo.http.governance.ReportService;
import com.echo.http.governance.ReportStore;
import com.echo.http.model.ModerationModels.CardStatus;
import com.echo.http.model.ModerationModels.MemoryCard;
import com.echo.http.model.ModerationModels.OriginType;
import com.echo.http.store.InMemoryModerationStore;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code S3} 三项治理能力（举报 / 拉黑 / 关互动）的端点与硬约束单测。
 *
 * <p>重点钉三条容易被做坏的约束：举报人身份不可泄漏、拉黑静默失效、全局关 &gt; 单条开。</p>
 */
class GovernanceApiTest {

    private static final long AUTHOR = 9001L;
    private static final long VISITOR = 9002L;
    private static final long OTHER = 9003L;
    private static final long CARD = 7001L;

    private InMemoryModerationStore cards;
    private BlockService blocks;
    private ReportService reports;
    private FeatureSwitchService switches;
    private CapabilityRegistry capabilities;
    private InteractionPolicy interactions;
    private GovernanceApi governance;
    private Router router;

    @BeforeEach
    void setUp() {
        IDGenerator ids = new IDGenerator(1L);
        cards = new InMemoryModerationStore(ids);
        blocks = new BlockService(new BlockStore(null), ids);
        reports = new ReportService(new ReportStore(null), ids);
        capabilities = new CapabilityRegistry();
        switches = new FeatureSwitchService(new FeatureSwitchStore(null), capabilities);
        interactions = new InteractionPolicy(switches);
        governance = new GovernanceApi(reports, blocks, interactions, switches, cards);
        router = new Router();
        governance.register(router);

        MemoryCard card = new MemoryCard();
        card.id = CARD;
        card.ownerId = AUTHOR;
        card.status = CardStatus.PUBLIC;
        card.visibilityIntent = "public";
        card.originType = OriginType.USER;
        cards.putCard(card);
    }

    private Object call(String method, String path, long accountId, JsonObject body) {
        Router.Match m = router.match(method, path);
        assertThat(m).as("route %s %s must match", method, path).isNotNull();
        RequestContext ctx = new RequestContext(method, m.pathParams, Map.of(),
                body == null ? new JsonObject() : body, accountId);
        try {
            return m.entry.route.handle(ctx);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JsonObject json(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            o.addProperty(kv[i], kv[i + 1]);
        }
        return o;
    }

    private void enableLeaveWords() {
        for (GovernanceCapability c : GovernanceCapability.values()) {
            capabilities.register(c, () -> true);
        }
        switches.setEnabled(FeatureSwitchService.KEY_LEAVE_WORDS, true, 1L, 2L);
    }

    // ==================================================================== 举报

    /** 三类对象都能举报。 */
    @Test
    void reportsCoverCardTextAndAccount() {
        assertThat(call("POST", "/reports", VISITOR,
                json("targetType", "card", "targetId", String.valueOf(CARD),
                        "reasonCode", "harassment"))).isNotNull();
        assertThat(call("POST", "/reports", VISITOR,
                json("targetType", "text", "targetId", "555", "reasonCode", "fraud"))).isNotNull();
        assertThat(call("POST", "/reports", VISITOR,
                json("targetType", "account", "targetId", String.valueOf(AUTHOR),
                        "reasonCode", "impersonation"))).isNotNull();
    }

    /**
     * 🔴 举报人身份不得对被举报方可见 —— 任何返回体都不能带 reporterId。
     *
     * <p>连"被举报对象是谁"也不回：出参只有 targetType，没有 targetId。举报人已经知道自己报了谁，
     * 而多回一个字段就多一条泄漏路径。</p>
     */
    @Test
    void reportResponseNeverLeaksReporterIdentity() {
        Object created = call("POST", "/reports", VISITOR,
                json("targetType", "card", "targetId", String.valueOf(CARD),
                        "reasonCode", "harassment"));
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) created;
        assertThat(m.keySet())
                .as("🔴 出参字段白名单，不得出现 reporterId")
                .containsExactlyInAnyOrder("id", "targetType", "state", "feedback", "createdAt");

        Object mine = call("GET", "/reports/mine", VISITOR, null);
        assertThat(mine.toString()).doesNotContain("reporterId");

        // 视图 record 本身就没有这个字段——想泄漏得先改定义，那是 CR 能看见的改动
        assertThat(ReportService.ReporterView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("reporterId", "targetId");
    }

    /** 🔴 反馈用词克制：不得出现「已处罚」「已封禁」这类表述。 */
    @Test
    void reporterFeedbackHasNoPunitiveWording() {
        for (String copy : new String[]{
                ReportService.FEEDBACK_RECEIVED, ReportService.FEEDBACK_REVIEWED}) {
            assertThat(copy).doesNotContain("处罚").doesNotContain("封禁").doesNotContain("封号")
                    .doesNotContain("删除").doesNotContain("违规").doesNotContain("下架")
                    .doesNotContain("举报成功");
        }
    }

    /** 防滥用：同一人对同一对象重复举报被去重。 */
    @Test
    void duplicateReportOnSameTargetIsRejected() {
        call("POST", "/reports", VISITOR,
                json("targetType", "card", "targetId", String.valueOf(CARD), "reasonCode", "porn"));
        assertThatThrownBy(() -> call("POST", "/reports", VISITOR,
                json("targetType", "card", "targetId", String.valueOf(CARD), "reasonCode", "porn")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ReportService.ERR_DUPLICATE_REPORT);

        // 换人可以报同一对象；换对象同一人也可以报
        call("POST", "/reports", OTHER,
                json("targetType", "card", "targetId", String.valueOf(CARD), "reasonCode", "porn"));
        call("POST", "/reports", VISITOR,
                json("targetType", "account", "targetId", String.valueOf(OTHER),
                        "reasonCode", "porn"));
    }

    /** ⚠️ 理由码字典未定稿，接口必须诚实说明（保留关位 + 诚实返回未就绪）。 */
    @Test
    void reasonDictIsHonestlyMarkedProvisional() {
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) call("GET", "/reports/reasons", VISITOR, null);
        assertThat(out.get("provisional")).isEqualTo(true);
    }

    /** 不能举报自己。 */
    @Test
    void cannotReportSelf() {
        assertThatThrownBy(() -> call("POST", "/reports", VISITOR,
                json("targetType", "account", "targetId", String.valueOf(VISITOR),
                        "reasonCode", "other")))
                .isInstanceOf(ApiException.class);
    }

    // ==================================================================== 拉黑

    /** 🔴 被拉黑方无法对拉黑方的内容产生新互动（不只是前端不显示）。 */
    @Test
    void blockedPartyCannotInteractWithBlockersContent() {
        assertThat(blocks.canInteract(AUTHOR, VISITOR)).isTrue();
        call("POST", "/accounts/" + VISITOR + "/block", AUTHOR, null);
        assertThat(blocks.canInteract(AUTHOR, VISITOR))
                .as("🔴 拉黑必须在服务端生效").isFalse();
    }

    /** 🔴 单向：作者拉黑访客，不影响访客被别人看/别人看访客。 */
    @Test
    void blockIsUnidirectional() {
        call("POST", "/accounts/" + VISITOR + "/block", AUTHOR, null);
        assertThat(blocks.canInteract(AUTHOR, VISITOR)).isFalse();
        assertThat(blocks.canInteract(VISITOR, AUTHOR))
                .as("反方向不受影响：访客并没有拉黑作者").isTrue();
    }

    /**
     * 🔴 静默失效：被拉黑方的互动请求返回<b>成功形状</b>，不抛错。
     *
     * <p>{@link GovernanceApi#interactionGuard} 返回 false 表示"假装成功"。这里断言的是
     * 它<b>不抛异常</b>——一旦有人把它改成抛 403，这条用例会失败。</p>
     */
    @Test
    void blockedInteractionFailsSilentlyNotLoudly() {
        call("POST", "/accounts/" + VISITOR + "/block", AUTHOR, null);
        // 不抛错，只返回 false（调用方据此回成功形状）
        assertThat(governance.interactionGuard(AUTHOR, VISITOR, null, null)).isFalse();
        assertThat(governance.interactionGuard(AUTHOR, OTHER, null, null)).isTrue();
    }

    /**
     * 🔴 被拉黑与被关互动<b>返回同一个结果</b>，不可区分。
     *
     * <p>若两者分开返回，被拉黑方可以对比响应差异把拉黑探测出来。</p>
     */
    @Test
    void blockAndClosedInteractionAreIndistinguishable() {
        enableLeaveWords();
        // 情形一：被拉黑
        call("POST", "/accounts/" + VISITOR + "/block", AUTHOR, null);
        boolean blockedResult = governance.interactionGuard(AUTHOR, VISITOR,
                InteractionPolicy.LEAVE_WORDS, null);
        // 情形二：作者关了这类互动（换个没被拉黑的人）
        boolean closedResult = governance.interactionGuard(AUTHOR, OTHER,
                InteractionPolicy.LEAVE_WORDS, "{\"leaveWords\":false}");
        assertThat(blockedResult).isEqualTo(closedResult).isFalse();
    }

    /** 拉黑幂等；解除拉黑恢复互动。 */
    @Test
    void blockIsIdempotentAndReversible() {
        call("POST", "/accounts/" + VISITOR + "/block", AUTHOR, null);
        call("POST", "/accounts/" + VISITOR + "/block", AUTHOR, null);
        assertThat(blocks.store().blockedList(AUTHOR)).containsExactly(VISITOR);

        call("DELETE", "/accounts/" + VISITOR + "/block", AUTHOR, null);
        assertThat(blocks.store().blockedList(AUTHOR)).isEmpty();
        assertThat(blocks.canInteract(AUTHOR, VISITOR)).isTrue();
    }

    /** 拉黑连带解除双向关注。 */
    @Test
    void blockUnlinksFollowsBothDirections() {
        StringBuilder calls = new StringBuilder();
        blocks.setFollowUnlinker((a, b) -> {
            calls.append(a).append("<->").append(b);
            return 2;
        });
        call("POST", "/accounts/" + VISITOR + "/block", AUTHOR, null);
        assertThat(calls.toString()).isEqualTo(AUTHOR + "<->" + VISITOR);

        // 幂等：重复拉黑不再触发解关注
        calls.setLength(0);
        call("POST", "/accounts/" + VISITOR + "/block", AUTHOR, null);
        assertThat(calls.toString()).isEmpty();
    }

    /** 不能拉黑自己。 */
    @Test
    void cannotBlockSelf() {
        assertThatThrownBy(() -> call("POST", "/accounts/" + AUTHOR + "/block", AUTHOR, null))
                .isInstanceOf(ApiException.class);
    }

    /** 拉黑名单只有本人能看。 */
    @Test
    void blockedListRequiresBinding() {
        assertThatThrownBy(() -> call("GET", "/accounts/blocked", 0L, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ApiException.UNAUTHORIZED);
    }

    // ================================================================== 关互动

    /** 作者可关闭自己单条内容的某类互动。 */
    @Test
    void authorCanCloseInteractionOnOwnCard() {
        JsonObject b = new JsonObject();
        b.addProperty(InteractionPolicy.REMEMBER, false);
        call("PATCH", "/cards/" + CARD + "/interaction", AUTHOR, b);

        assertThat(interactions.enabled(InteractionPolicy.REMEMBER,
                cards.card(CARD).interactionJson)).isFalse();
        assertThat(interactions.enabled(InteractionPolicy.FOOTPRINT,
                cards.card(CARD).interactionJson))
                .as("没动的键仍是开启（缺键 = 开）").isTrue();
    }

    /** 非作者不能改别人的卡，且不泄漏"这张卡存在但不是你的"。 */
    @Test
    void nonAuthorCannotChangeInteraction() {
        JsonObject b = new JsonObject();
        b.addProperty(InteractionPolicy.REMEMBER, false);
        assertThatThrownBy(() -> call("PATCH", "/cards/" + CARD + "/interaction", VISITOR, b))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ApiException.NOT_FOUND);
    }

    /**
     * 🔴 <b>全局关 &gt; 单条开</b>：全局开关关闭时，作者把 leaveWords 设成 true 也不生效。
     *
     * <p>这条做反了，任何作者都能用一条自己可写的 json 绕过 {@code S13} 的整套前置校验。</p>
     */
    @Test
    void globalOffBeatsPerCardOn() {
        assertThat(switches.isLeaveWordsEnabled()).as("P0 默认关闭").isFalse();

        JsonObject b = new JsonObject();
        b.addProperty(InteractionPolicy.LEAVE_WORDS, true);
        call("PATCH", "/cards/" + CARD + "/interaction", AUTHOR, b);

        assertThat(interactions.enabled(InteractionPolicy.LEAVE_WORDS,
                cards.card(CARD).interactionJson))
                .as("🔴 单条不得反向打开被全局关掉的能力").isFalse();
        assertThat(interactions.authorAllows(InteractionPolicy.LEAVE_WORDS,
                cards.card(CARD).interactionJson))
                .as("作者的意图仍被记录下来，只是不生效").isTrue();
    }

    /** 全局开之后，作者的单条设置才起作用；单条关仍然优先于全局开。 */
    @Test
    void perCardOffStillBeatsGlobalOn() {
        enableLeaveWords();
        assertThat(interactions.enabled(InteractionPolicy.LEAVE_WORDS, null))
                .as("全局开 + 作者未设置 = 可用").isTrue();

        JsonObject b = new JsonObject();
        b.addProperty(InteractionPolicy.LEAVE_WORDS, false);
        call("PATCH", "/cards/" + CARD + "/interaction", AUTHOR, b);
        assertThat(interactions.enabled(InteractionPolicy.LEAVE_WORDS,
                cards.card(CARD).interactionJson))
                .as("作者关了就是关了").isFalse();
    }

    /** {@code R4 我也想起一件事} 同受留一句话开关门控（它同样是自由文本）。 */
    @Test
    void meTooIsGatedByTheSameSwitch() {
        assertThat(interactions.enabled(InteractionPolicy.ME_TOO, null)).isFalse();
        enableLeaveWords();
        assertThat(interactions.enabled(InteractionPolicy.ME_TOO, null)).isTrue();
    }

    /** 不受开关门控的互动（记得/留脚印/心意）默认可用。 */
    @Test
    void ungatedInteractionsDefaultOn() {
        assertThat(interactions.enabled(InteractionPolicy.REMEMBER, null)).isTrue();
        assertThat(interactions.enabled(InteractionPolicy.FOOTPRINT, null)).isTrue();
        assertThat(interactions.enabled(InteractionPolicy.SHARED_FLOWER, null)).isTrue();
    }

    /** 🔴 未知键丢弃：interaction 不能变成用户可写的任意 json 存储位。 */
    @Test
    void unknownInteractionKeysAreDropped() {
        JsonObject b = new JsonObject();
        b.addProperty(InteractionPolicy.REMEMBER, false);
        b.addProperty("evilKey", "payload");
        call("PATCH", "/cards/" + CARD + "/interaction", AUTHOR, b);
        assertThat(cards.card(CARD).interactionJson).doesNotContain("evilKey");
    }

    /** 空 body 不算合法请求。 */
    @Test
    void emptyInteractionPatchIsRejected() {
        assertThatThrownBy(() -> call("PATCH", "/cards/" + CARD + "/interaction", AUTHOR,
                new JsonObject()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ApiException.BAD_PARAM);
    }

    /** 🔴 关互动写入只碰 interaction 与 updatedAt，不得成为改 originType/reviewedAt 的旁路。 */
    @Test
    void interactionUpdateTouchesNothingElse() {
        MemoryCard before = cards.card(CARD);
        String originType = before.originType;
        Long reviewedAt = before.reviewedAt;
        String status = before.status;

        JsonObject b = new JsonObject();
        b.addProperty(InteractionPolicy.REMEMBER, false);
        call("PATCH", "/cards/" + CARD + "/interaction", AUTHOR, b);

        MemoryCard after = cards.card(CARD);
        assertThat(after.originType).isEqualTo(originType);
        assertThat(after.reviewedAt).isEqualTo(reviewedAt);
        assertThat(after.status).isEqualTo(status);
    }
}
