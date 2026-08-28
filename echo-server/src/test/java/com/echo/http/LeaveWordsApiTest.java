package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.governance.BlockService;
import com.echo.http.governance.BlockStore;
import com.echo.http.governance.CapabilityRegistry;
import com.echo.http.governance.FeatureSwitchService;
import com.echo.http.governance.FeatureSwitchStore;
import com.echo.http.governance.GovernanceCapability;
import com.echo.http.governance.InteractionPolicy;
import com.echo.http.governance.LeaveWordsStore;
import com.echo.http.governance.ReportService;
import com.echo.http.governance.ReportStore;
import com.echo.http.model.ModerationModels.CardStatus;
import com.echo.http.model.ModerationModels.MemoryCard;
import com.echo.http.model.ModerationModels.OriginType;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.safety.OutputSafetyGate;
import com.echo.http.safety.SafetyMetrics;
import com.echo.http.store.EchoStore;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.http.store.InMemoryModerationStore;
import com.echo.infra.safety.ContentSafetyConfig;
import com.echo.infra.safety.ContentSafetyGate;
import com.echo.infra.safety.ContentSafetyVerdict;
import com.echo.infra.safety.FakeContentSafetyClient;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code C1 留一句话}四端点的单测（{@code S13} · {@code PALETTE §56/§186}）。
 *
 * <p>本套用例的重心不在「留言能存下来」，而在两组<b>不能出现</b>的东西：</p>
 *
 * <ul>
 *   <li>🔴 <b>回执一致性</b>：开关关着 / 被拉黑 / 作者关了留言 / 被安全闸拦下 —— 四种情形的
 *       响应必须<b>逐字相同</b>。任何一种能被区分出来，留言者就能反推出「我被拉黑了」
 *       或「我被拒了」，而那两件事都是明令不得让他知道的。</li>
 *   <li>🔴 <b>P0 默认关闭</b>：不做任何设置时，开关就是关的，且它<b>打不开</b>
 *       （治理能力未就绪）。</li>
 * </ul>
 */
class LeaveWordsApiTest {

    private static final long AUTHOR = 9101L;
    private static final long VISITOR = 9102L;
    private static final long STRANGER = 9103L;
    private static final long CARD = 7101L;

    private InMemoryModerationStore cards;
    private EchoStore accounts;
    private LeaveWordsStore words;
    private BlockService blocks;
    private CapabilityRegistry capabilities;
    private FeatureSwitchService switches;
    private SafetyMetrics metrics;
    private Router router;

    @BeforeEach
    void setUp() {
        setUpWithSafety(text -> ContentSafetyVerdict.pass());
    }

    private void setUpWithSafety(java.util.function.Function<String, ContentSafetyVerdict> safety) {
        IDGenerator ids = new IDGenerator(1L);
        cards = new InMemoryModerationStore(ids);
        accounts = new InMemoryEchoStore();
        words = new LeaveWordsStore(null);
        blocks = new BlockService(new BlockStore(null), ids);
        capabilities = new CapabilityRegistry();
        switches = new FeatureSwitchService(new FeatureSwitchStore(null), capabilities);
        InteractionPolicy interactions = new InteractionPolicy(switches);
        GovernanceApi governance = new GovernanceApi(
                new ReportService(new ReportStore(null), ids), blocks, interactions, switches, cards);
        metrics = new SafetyMetrics();
        OutputSafetyGate gate = new OutputSafetyGate(metrics, new ContentSafetyGate(
                ContentSafetyConfig.fake(), new FakeContentSafetyClient(safety)));

        router = new Router();
        governance.register(router);
        new LeaveWordsApi(words, cards, accounts, governance, switches, gate, ids).register(router);

        MemoryCard card = new MemoryCard();
        card.id = CARD;
        card.ownerId = AUTHOR;
        card.status = CardStatus.PUBLIC;
        card.visibilityIntent = "public";
        card.originType = OriginType.USER;
        cards.putCard(card);

        profile(AUTHOR, "拾光");
        profile(VISITOR, "远山");
        profile(STRANGER, "阿岸");
    }

    private void profile(long accountId, String nickname) {
        AccountProfile p = new AccountProfile();
        p.accountId = accountId;
        p.nickname = nickname;
        p.guest = false;
        accounts.putProfile(p);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String method, String path, long accountId, JsonObject body) {
        Router.Match m = router.match(method, path);
        assertThat(m).as("route %s %s must match", method, path).isNotNull();
        RequestContext ctx = new RequestContext(method, m.pathParams, Map.of(),
                body == null ? new JsonObject() : body, accountId);
        try {
            return (Map<String, Object>) m.entry.route.handle(ctx);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 把五项治理能力全标就绪，再由另一个人复核着打开开关。 */
    private void openSwitch() {
        for (GovernanceCapability c : GovernanceCapability.values()) {
            capabilities.register(c, () -> true);
        }
        switches.setEnabled(FeatureSwitchService.KEY_LEAVE_WORDS, true, 1L, 2L);
    }

    private Map<String, Object> leave(long who, String text) {
        JsonObject b = new JsonObject();
        b.addProperty("text", text);
        return call("POST", "/cards/" + CARD + "/messages", who, b);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pending(long who) {
        Map<String, Object> page = call("GET", "/cards/" + CARD + "/messages/pending", who, null);
        assertThat(page).containsKeys("items", "nextCursor");
        return (List<Map<String, Object>>) page.get("items");
    }

    // ============================================================ 开关默认关闭

    @Test
    void flagIsOffByDefault() {
        Map<String, Object> flags = call("GET", "/config/flags", VISITOR, null);
        assertThat(flags.get("leaveMessage"))
                .as("🔴 P0 默认关闭 —— 这不是缺省值凑巧如此，是裁定的一部分").isEqualTo(false);
    }

    /** 🔴 治理能力未就绪时，开关<b>写不进去</b>——不是写进去再告警。 */
    @Test
    void switchCannotBeOpenedBeforeCapabilitiesAreReady() {
        assertThatThrownBy(() ->
                switches.setEnabled(FeatureSwitchService.KEY_LEAVE_WORDS, true, 1L, 2L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(FeatureSwitchService.ERR_CAPABILITY_NOT_READY));

        assertThat(call("GET", "/config/flags", VISITOR, null).get("leaveMessage")).isEqualTo(false);
    }

    @Test
    void flagTurnsOnOnlyAfterCapabilitiesAndSecondApproval() {
        openSwitch();
        assertThat(call("GET", "/config/flags", VISITOR, null).get("leaveMessage")).isEqualTo(true);
    }

    // ==================================================== 🔴 四种情形回执一致

    /** 开关关着：回执是成功形状，但一个字都没落库。 */
    @Test
    void switchOffLooksLikeSuccessButStoresNothing() {
        assertThat(leave(VISITOR, "它一定很想你")).isEqualTo(Map.of("ok", true));
        assertThat(words.existing(CARD, VISITOR))
                .as("🔴 开关关着就不该有任何落库").isNull();
    }

    /** 被拉黑：同样的成功形状。🔴 与开关关着、与正常成功，三者逐字相同。 */
    @Test
    void blockedVisitorGetsIdenticalReceipt() {
        openSwitch();
        blocks.block(AUTHOR, VISITOR);

        Map<String, Object> blockedReceipt = leave(VISITOR, "它一定很想你");
        Map<String, Object> normalReceipt = leave(STRANGER, "它一定很想你");
        assertThat(blockedReceipt)
                .as("🔴 回执一旦有差异，被拉黑方就能把拉黑探测出来").isEqualTo(normalReceipt);

        assertThat(words.existing(CARD, VISITOR)).as("被拉黑者的留言不落库").isNull();
        assertThat(words.existing(CARD, STRANGER)).as("正常留言要落库").isNotNull();
    }

    /** 作者关了这张卡的留言：同样的成功形状，同样不落库。 */
    @Test
    void authorClosedLeaveWordsGetsIdenticalReceipt() {
        openSwitch();
        JsonObject patch = new JsonObject();
        patch.addProperty(InteractionPolicy.LEAVE_WORDS, false);
        call("PATCH", "/cards/" + CARD + "/interaction", AUTHOR, patch);

        assertThat(leave(VISITOR, "它一定很想你")).isEqualTo(Map.of("ok", true));
        assertThat(words.existing(CARD, VISITOR)).isNull();
    }

    /**
     * 被文本安全闸拦下：回执依旧是成功形状（{@code I-05} 绝不显示被拒绝），
     * 🔴 但落库时标 {@code rejected}，并且<b>不进作者的待处理队列</b>。
     */
    @Test
    void safetyRejectedTextIsSilentAndNeverReachesTheAuthor() {
        setUpWithSafety(text -> ContentSafetyVerdict.blocked("涉政"));
        openSwitch();

        assertThat(leave(VISITOR, "一段违规内容")).isEqualTo(Map.of("ok", true));

        LeaveWordsStore.Entry stored = words.existing(CARD, VISITOR);
        assertThat(stored).as("🔴 不物理删：「被闸拦下」与「从来没人留过」对深共鸣率分母是相反的答案")
                .isNotNull();
        assertThat(stored.safetyState).isEqualTo(LeaveWordsStore.SAFETY_REJECTED);
        assertThat(pending(AUTHOR))
                .as("🔴 不该把违规内容送到作者眼前还要他点一下「不留」").isEmpty();
    }

    /** 🔴 用户文本的拦截不得混进输出侧拦截率的分母（同 S12 ② 的理由）。 */
    @Test
    void userTextInterceptionStaysOutOfTheOutputDenominator() {
        setUpWithSafety(text -> ContentSafetyVerdict.blocked("涉政"));
        openSwitch();
        leave(VISITOR, "一段违规内容");

        assertThat(metrics.userTextBlockedCount()).isEqualTo(1);
        assertThat(metrics.outputInspectedCount())
                .as("🔴 用户来发违规内容，与我们的模型产出违规内容，是两个数字").isZero();
    }

    // ================================================== 🔴 回执不带任何可查询句柄

    @Test
    void receiptCarriesNoQueryableHandle() {
        openSwitch();
        Map<String, Object> receipt = leave(VISITOR, "它一定很想你");
        assertThat(receipt).containsOnlyKeys("ok");
        // 有 id/state 就迟早有人做一个「我留的话怎么样了」的界面，那条红线等于没有
        assertThat(receipt).doesNotContainKeys("id", "messageId", "state", "disposition", "safetyState");
    }

    // ========================================================== 长度与格式校验

    @Test
    void rejectsTextLongerThanSixtyChars() {
        openSwitch();
        String tooLong = "念".repeat(LeaveWordsApi.MAX_CHARS + 1);
        assertThatThrownBy(() -> leave(VISITOR, tooLong))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.BAD_PARAM));
    }

    @Test
    void acceptsExactlySixtyChars() {
        openSwitch();
        assertThat(leave(VISITOR, "念".repeat(LeaveWordsApi.MAX_CHARS))).isEqualTo(Map.of("ok", true));
        assertThat(words.existing(CARD, VISITOR)).isNotNull();
    }

    @Test
    void rejectsBlankText() {
        openSwitch();
        assertThatThrownBy(() -> leave(VISITOR, "   "))
                .isInstanceOf(ApiException.class);
    }

    /**
     * 🔴 60 字是<b>用户数得出来的那个数</b>：一个 emoji 算一个字，不算两个。
     *
     * <p>按 {@code String.length()} 判的话，代理对占两格，60 个 emoji 会被判成 120 字当场拒掉，
     * 而用户看着屏幕上的 60 个符号完全不明白哪里超了。这条用例钉住 {@code codePointCount}。</p>
     */
    @Test
    void surrogatePairsCountAsOneCharEach() {
        openSwitch();
        String sixtyPaws = "\uD83D\uDC3E".repeat(LeaveWordsApi.MAX_CHARS);
        assertThat(sixtyPaws.length()).as("前提：这串按 length() 数是超的").isEqualTo(LeaveWordsApi.MAX_CHARS * 2);

        assertThat(leave(VISITOR, sixtyPaws)).isEqualTo(Map.of("ok", true));
        assertThat(words.existing(CARD, VISITOR)).isNotNull();

        assertThatThrownBy(() -> leave(STRANGER, "\uD83D\uDC3E".repeat(LeaveWordsApi.MAX_CHARS + 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.BAD_PARAM));
    }

    /**
     * 格式校验排在闸门之前：开关关着时，超长文本照样报「太长了」。
     *
     * <p>🔴 <b>这不构成信息泄漏，反而是必须的。</b>格式错误对所有人一视同仁——
     * 开关关着、被拉黑、一切正常，三种情形下超长文本得到的都是同一个 BAD_PARAM，
     * 所以它区分不出任何东西。反过来若把格式错误也吞成静默成功，
     * 一个只是打多了字的人会以为话已经留下了。</p>
     */
    @Test
    void formatErrorsAreReportedEvenWhileTheSwitchIsOff() {
        String tooLong = "念".repeat(LeaveWordsApi.MAX_CHARS + 1);
        assertThatThrownBy(() -> leave(VISITOR, tooLong))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.BAD_PARAM));
        // 开关关着，仍然一个字都没落库
        assertThat(words.existing(CARD, VISITOR)).isNull();
    }

    @Test
    void rejectsNonNumericWindowId() {
        openSwitch();
        JsonObject b = new JsonObject();
        b.addProperty("text", "它一定很想你");
        assertThatThrownBy(() -> call("POST", "/cards/not-an-id/messages", VISITOR, b))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.BAD_PARAM));
    }

    /** 相对时间粒度到天，再往前只说「很久以前」——不把具体日期摆到作者眼前。 */
    @Test
    void relativeTimeCollapsesBeyondAMonth() {
        openSwitch();
        leave(VISITOR, "它一定很想你");
        LeaveWordsStore.Entry e = words.existing(CARD, VISITOR);
        e.createdAt = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000;

        assertThat(pending(AUTHOR).get(0).get("time")).isEqualTo("很久以前");

        e.createdAt = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000;
        assertThat(pending(AUTHOR).get(0).get("time")).isEqualTo("3 天前");
    }

    @Test
    void guestCannotLeaveWords() {
        openSwitch();
        assertThatThrownBy(() -> leave(0L, "它一定很想你"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.UNAUTHORIZED));
    }

    // ================================================ 🔴 一人对一张卡只留一句

    @Test
    void onePersonOneCardOneMessage() {
        openSwitch();
        leave(VISITOR, "第一句");
        // 🔴 回执仍是 ok：告诉他「你已经留过了」也是可被利用的信号
        assertThat(leave(VISITOR, "第二句")).isEqualTo(Map.of("ok", true));

        assertThat(words.existing(CARD, VISITOR).body)
                .as("🔴 不覆盖 —— 反骚扰靠结构保证，不靠前端 disable").isEqualTo("第一句");
        assertThat(pending(AUTHOR)).hasSize(1);
    }

    // ============================================================ 作者侧三选一

    @Test
    void pendingQueueIsVisibleToTheAuthorOnly() {
        openSwitch();
        leave(VISITOR, "它一定很想你");

        List<Map<String, Object>> mine = pending(AUTHOR);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0)).containsKeys("id", "authorName", "authorAvatar", "text", "time");
        assertThat(mine.get(0).get("authorName")).isEqualTo("远山");
        assertThat(mine.get(0).get("text")).isEqualTo("它一定很想你");

        // 🔴 非窗主一律空、不报错：报错会把「这张卡有没有待处理留言」变成可探测信号
        assertThat(pending(STRANGER)).isEmpty();
        assertThat(pending(VISITOR)).as("留言者自己也看不到").isEmpty();
    }

    @Test
    void authorPublishesMessage() {
        openSwitch();
        leave(VISITOR, "它一定很想你");
        String id = (String) pending(AUTHOR).get(0).get("id");

        assertThat(resolve(id, AUTHOR, "publish")).isEqualTo(Map.of("ok", true));
        assertThat(pending(AUTHOR)).as("处理过就出队").isEmpty();
        assertThat(words.publicOf(CARD, 10)).hasSize(1);
    }

    /** 「不留」不删行，也不进公开列表。 */
    @Test
    void authorDropsMessageWithoutDeletingTheRow() {
        openSwitch();
        leave(VISITOR, "它一定很想你");
        String id = (String) pending(AUTHOR).get(0).get("id");

        resolve(id, AUTHOR, "drop");
        assertThat(pending(AUTHOR)).isEmpty();
        assertThat(words.publicOf(CARD, 10)).isEmpty();

        LeaveWordsStore.Entry stored = words.existing(CARD, VISITOR);
        assertThat(stored).as("🔴 「作者拒了」与「从来没人留过」必须可区分").isNotNull();
        assertThat(stored.disposition).isEqualTo(LeaveWordsStore.DECLINED);
    }

    @Test
    void privateDispositionKeepsItOutOfThePublicList() {
        openSwitch();
        leave(VISITOR, "它一定很想你");
        String id = (String) pending(AUTHOR).get(0).get("id");

        resolve(id, AUTHOR, "private");
        assertThat(words.publicOf(CARD, 10)).isEmpty();
        assertThat(words.existing(CARD, VISITOR).disposition).isEqualTo(LeaveWordsStore.PRIVATE);
    }

    /** 🔴 非作者处理不了，且响应与「不存在」一致——不能变成探测留言存在性的工具。 */
    @Test
    void nonAuthorCannotResolveAndCannotTellItExists() {
        openSwitch();
        leave(VISITOR, "它一定很想你");
        String id = (String) pending(AUTHOR).get(0).get("id");

        ApiException byStranger = catchApi(() -> resolve(id, STRANGER, "publish"));
        ApiException byMissing = catchApi(() -> resolve("999999", STRANGER, "publish"));
        assertThat(byStranger.code()).isEqualTo(ApiException.NOT_FOUND);
        assertThat(byStranger.getMessage()).isEqualTo(byMissing.getMessage());
    }

    @Test
    void rejectsUnknownDisposition() {
        openSwitch();
        leave(VISITOR, "它一定很想你");
        String id = (String) pending(AUTHOR).get(0).get("id");

        assertThatThrownBy(() -> resolve(id, AUTHOR, "reject"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.BAD_PARAM));
    }

    /** 作者重复点一下不该看到报错。 */
    @Test
    void resolvingTwiceIsIdempotent() {
        openSwitch();
        leave(VISITOR, "它一定很想你");
        String id = (String) pending(AUTHOR).get(0).get("id");

        resolve(id, AUTHOR, "publish");
        assertThat(resolve(id, AUTHOR, "drop")).isEqualTo(Map.of("ok", true));
        assertThat(words.existing(CARD, VISITOR).disposition)
                .as("已处理过就不再改动").isEqualTo(LeaveWordsStore.PUBLIC);
    }

    private Map<String, Object> resolve(String messageId, long who, String disposition) {
        JsonObject b = new JsonObject();
        b.addProperty("disposition", disposition);
        return call("POST", "/messages/" + messageId + "/disposition", who, b);
    }

    private static ApiException catchApi(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("期望抛 ApiException，但没有抛");
    }
}
