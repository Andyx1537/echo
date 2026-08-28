package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.model.Models.ReactionMark;
import com.echo.http.store.EchoStore;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.infra.corpus.InMemoryTrainingCorpus;
import com.echo.infra.llm.MockLlmClient;
import com.echo.infra.vision.StubVisionClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「被接住」到达端点的服务端单测（{@code PRODUCT-MINDMAP §6.2 B20} · {@code D22}）。
 *
 * <p>钉住三件事：① 献花与记得归一成同一种到达；② 🔴 <b>同一张卡的回应永远在同一页</b>
 * ——这是「一张卡只出一条」在分页下唯一的守法；③ 看过即散是整卡散，且散不掉之后新来的。</p>
 *
 * <p>全内存态、外部服务 mock，不依赖 DB，也不发起任何网络调用。</p>
 */
class ReactionArrivalsTest {

    private EchoStore store;
    private Router router;

    @BeforeEach
    void setUp() {
        store = new InMemoryEchoStore();
        EchoApi api = new EchoApi(store, new IDGenerator(1L), new MockLlmClient(), new StubVisionClient(),
                null, new InMemoryTrainingCorpus());
        router = api.routes(true);
    }

    // ------------------------------------------------------------------ 脚手架

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String method, String path, long accountId,
                                       Map<String, String> query, JsonObject body) throws Exception {
        Router.Match m = router.match(method, path);
        assertThat(m).as("route %s %s must match", method, path).isNotNull();
        RequestContext ctx = new RequestContext(method, m.pathParams, query,
                body == null ? new JsonObject() : body, accountId);
        return (Map<String, Object>) m.entry.route.handle(ctx);
    }

    private Map<String, Object> invoke(String method, String path, long accountId, JsonObject body) throws Exception {
        return invoke(method, path, accountId, Map.of(), body);
    }

    private long guest(String deviceId) throws Exception {
        JsonObject b = new JsonObject();
        b.addProperty("deviceId", deviceId);
        return Long.parseLong((String) invoke("POST", "/auth/guest", 0, b).get("accountId"));
    }

    private String createPet(long accountId, String name) throws Exception {
        JsonObject start = new JsonObject();
        start.addProperty("petName", name);
        String onboardingId = (String) invoke("POST", "/pet/onboarding/start", accountId, start).get("onboardingId");

        JsonObject confirm = new JsonObject();
        confirm.addProperty("onboardingId", onboardingId);
        confirm.addProperty("finalCandidateId", "any");
        JsonObject scene = new JsonObject();
        scene.addProperty("caption", "相遇那天");
        scene.addProperty("allowUse", true);
        confirm.add("memoryScene", scene);
        return (String) invoke("POST", "/pet/onboarding/confirm", accountId, confirm).get("petId");
    }

    private void remember(long actor, String windowId) throws Exception {
        JsonObject b = new JsonObject();
        b.addProperty("remembered", true);
        invoke("POST", "/windows/" + windowId + "/remember", actor, b);
    }

    private void flower(long actor, String windowId) throws Exception {
        JsonObject b = new JsonObject();
        b.addProperty("count", 1);
        invoke("POST", "/windows/" + windowId + "/flower", actor, b);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> arrivals(long accountId, Map<String, String> query) throws Exception {
        Map<String, Object> page = invoke("GET", "/messages/arrivals", accountId, query, null);
        return (List<Map<String, Object>>) page.get("items");
    }

    private List<Map<String, Object>> arrivals(long accountId) throws Exception {
        return arrivals(accountId, Map.of());
    }

    private Map<String, Object> readArrivals(long accountId, String... cardIds) throws Exception {
        JsonArray ids = new JsonArray();
        for (String c : cardIds) {
            ids.add("arrival:" + c);
        }
        JsonObject b = new JsonObject();
        b.add("ids", ids);
        return invoke("POST", "/messages/read", accountId, b);
    }

    // ------------------------------------------------------------------ 归一

    /** 献花与记得在库里形状不同，到达这一层是同一件事：有人来过。 */
    @Test
    void flowerAndRememberBothArriveInOneShape() throws Exception {
        long owner = guest("dev-owner");
        String win = createPet(owner, "麦麦");
        remember(guest("dev-a"), win);
        flower(guest("dev-b"), win);

        List<Map<String, Object>> items = arrivals(owner);

        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(it -> {
            assertThat(it.get("cardId")).isEqualTo(win);
            assertThat(it.get("cardTitle")).isEqualTo("麦麦");
            assertThat((Long) it.get("createdAt")).isPositive();
        });
        assertThat(items).extracting(it -> it.get("reaction"))
                .containsExactlyInAnyOrder("remember", "flower");
    }

    /** 🔴 自己给自己献一束花不是「被接住」。 */
    @Test
    void ownReactionsOnOwnWindowNeverArrive() throws Exception {
        long owner = guest("dev-owner");
        String win = createPet(owner, "麦麦");
        remember(owner, win);
        flower(owner, win);

        assertThat(arrivals(owner)).isEmpty();
    }

    /** 没有任何回应时是干净的空页，不是 404。 */
    @Test
    void emptyWhenNobodyHasReacted() throws Exception {
        long owner = guest("dev-owner");
        createPet(owner, "麦麦");

        Map<String, Object> page = invoke("GET", "/messages/arrivals", owner, Map.of(), null);
        assertThat((List<?>) page.get("items")).isEmpty();
        assertThat(page.get("nextCursor")).isNull();
    }

    // -------------------------------------------------------- 🔴 分页 × 合并

    /**
     * 🔴 本文件最要紧的一条：<b>翻遍所有页，同一张卡也只会出现在其中一页里。</b>
     *
     * <p>卡一旦被切到两页，前端按卡合并就会合出两条通知，「一张卡只出一条」当场被打穿。
     * 这里让游标按卡推进，冲突在服务端就不成立。</p>
     */
    @Test
    void oneCardNeverStraddlesTwoPages() throws Exception {
        long owner = guest("dev-owner");
        String win = createPet(owner, "麦麦");
        // 五个人回应同一张卡，页大小却只有 1 张卡——最容易把一张卡切开的配置
        for (int i = 0; i < 5; i++) {
            remember(guest("dev-r" + i), win);
            flower(guest("dev-f" + i), win);
        }

        Map<String, Set<String>> cardToPages = new HashMap<>();
        String cursor = null;
        int pages = 0;
        do {
            Map<String, String> q = new HashMap<>();
            q.put("limit", "1");
            if (cursor != null) {
                q.put("cursor", cursor);
            }
            Map<String, Object> page = invoke("GET", "/messages/arrivals", owner, q, null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
            for (Map<String, Object> it : items) {
                cardToPages.computeIfAbsent((String) it.get("cardId"), k -> new LinkedHashSet<>())
                        .add("page-" + pages);
            }
            cursor = (String) page.get("nextCursor");
            pages++;
            assertThat(pages).as("翻页不该停不下来").isLessThan(20);
        } while (cursor != null);

        assertThat(cardToPages).containsOnlyKeys(win);
        assertThat(cardToPages.get(win)).as("这张卡被切到了多页，前端会合出多条通知").hasSize(1);
    }

    /** 热卡不撑爆一页：每张卡每类回应只下发最新那一行，合并需要的信息一样不少。 */
    @Test
    void hotCardCollapsesToAtMostOneRowPerKind() throws Exception {
        long owner = guest("dev-owner");
        String win = createPet(owner, "麦麦");
        for (int i = 0; i < 30; i++) {
            remember(guest("dev-r" + i), win);
        }

        List<Map<String, Object>> items = arrivals(owner);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("reaction")).isEqualTo("remember");
    }

    /** 多张卡按「最近一次被回应」倒序，刚热起来的窗浮在前面。 */
    @Test
    void cardsAreOrderedByMostRecentReaction() throws Exception {
        long ownerA = guest("dev-owner-a");
        String older = createPet(ownerA, "麦麦");
        remember(guest("dev-x"), older);

        // 同一个 owner 只能有一扇窗，所以第二张卡换个 owner 建，再把归属改到 A 名下
        long ownerB = guest("dev-owner-b");
        String newer = createPet(ownerB, "团团");
        var pet = store.petById(newer);
        pet.ownerAccountId = ownerA;
        store.putPet(pet);
        Thread.sleep(2);
        remember(guest("dev-y"), newer);

        List<Map<String, Object>> items = arrivals(ownerA);

        assertThat(items).extracting(it -> it.get("cardId")).containsExactly(newer, older);
    }

    // --------------------------------------------------------- 看过即散（B23）

    /** 没散过之前一律未读。 */
    @Test
    void arrivalsStartUnread() throws Exception {
        long owner = guest("dev-owner");
        String win = createPet(owner, "麦麦");
        remember(guest("dev-a"), win);

        assertThat(arrivals(owner))
                .as("🔴 空列表会让 allSatisfy 恒过 —— 那时这条用例什么都没验（见 BUILD-VERIFICATION §6）")
                .isNotEmpty()
                .allSatisfy(it -> assertThat(it.get("read")).isEqualTo(false));
    }

    /** 传 {@code arrival:<cardId>} 进 /messages/read，整张卡的回应一起散掉。 */
    @Test
    void readingMergedArrivalClearsWholeCard() throws Exception {
        long owner = guest("dev-owner");
        String win = createPet(owner, "麦麦");
        remember(guest("dev-a"), win);
        flower(guest("dev-b"), win);

        Map<String, Object> res = readArrivals(owner, win);

        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(arrivals(owner))
                .as("🔴 空列表会让 allSatisfy 恒过 —— 那时这条用例什么都没验（见 BUILD-VERIFICATION §6）")
                .isNotEmpty()
                .allSatisfy(it -> assertThat(it.get("read")).isEqualTo(true));
    }

    /**
     * 🔴 散过之后再来的回应仍然是未读。
     *
     * <p>水位推到「此刻最新一条」而不是 {@code now()}，就是为了这一条：
     * 请求在途时到的那条回应还没被谁看见，用 {@code now()} 会把它悄悄吞掉。</p>
     */
    @Test
    void reactionsArrivingAfterTheSweepStayUnread() throws Exception {
        long owner = guest("dev-owner");
        String win = createPet(owner, "麦麦");
        remember(guest("dev-a"), win);
        readArrivals(owner, win);
        Thread.sleep(2);
        flower(guest("dev-b"), win);

        List<Map<String, Object>> items = arrivals(owner);
        Map<String, Object> byKind = new HashMap<>();
        for (Map<String, Object> it : items) {
            byKind.put((String) it.get("reaction"), it.get("read"));
        }
        assertThat(byKind.get("remember")).isEqualTo(true);
        assertThat(byKind.get("flower")).as("散过之后新来的这一朵不该跟着一起散掉").isEqualTo(false);
    }

    /** 不是窗主，传别人的 cardId 进来散不掉任何东西。 */
    @Test
    void onlyTheOwnerCanSweepACard() throws Exception {
        long owner = guest("dev-owner");
        String win = createPet(owner, "麦麦");
        long stranger = guest("dev-stranger");
        remember(stranger, win);

        Map<String, Object> res = readArrivals(stranger, win);

        assertThat(res.get("updated")).isEqualTo(0);
        assertThat(arrivals(owner))
                .as("🔴 空列表会让 allSatisfy 恒过 —— 那时这条用例什么都没验（见 BUILD-VERIFICATION §6）")
                .isNotEmpty()
                .allSatisfy(it -> assertThat(it.get("read")).isEqualTo(false));
    }

    /** 普通消息 id 与到达 id 混在同一次请求里，各走各的路。 */
    @Test
    void ordinaryMessageIdsAndArrivalIdsCoexistInOneRequest() throws Exception {
        long owner = guest("dev-owner");
        String win = createPet(owner, "麦麦");
        remember(guest("dev-a"), win);

        JsonArray ids = new JsonArray();
        ids.add("arrival:" + win);
        ids.add("no-such-message");
        JsonObject b = new JsonObject();
        b.add("ids", ids);
        Map<String, Object> res = invoke("POST", "/messages/read", owner, b);

        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(arrivals(owner))
                .as("🔴 空列表会让 allSatisfy 恒过 —— 那时这条用例什么都没验（见 BUILD-VERIFICATION §6）")
                .isNotEmpty()
                .allSatisfy(it -> assertThat(it.get("read")).isEqualTo(true));
    }

    // ------------------------------------------------------------------ 红线

    /**
     * 🔴 到达行里不许出现人数，也不许出现回应者是谁。
     *
     * <p>人数一旦下发，迟早被渲染成「3 个人记得了它」；回应者一旦下发，
     * 通知就会自己长出一个面孔列表，而那是窗里那面暖光墙的事。</p>
     */
    @Test
    void arrivalRowsCarryNeitherHeadcountNorActor() throws Exception {
        long owner = guest("dev-owner");
        String win = createPet(owner, "麦麦");
        long actor = guest("dev-a");
        remember(actor, win);
        flower(guest("dev-b"), win);

        List<Map<String, Object>> items = arrivals(owner);

        assertThat(items).isNotEmpty();
        for (Map<String, Object> it : items) {
            assertThat(it.keySet())
                    .containsExactlyInAnyOrder("id", "cardId", "cardTitle", "reaction", "createdAt", "read");
            assertThat(new ArrayList<>(it.values()))
                    .doesNotContain(String.valueOf(actor))
                    .doesNotContain(actor);
        }
    }

    // -------------------------------------------------- 🔴 折叠的等价性前提

    /**
     * 🔴 前端复验时钉出来的反例：<b>同一张卡、同一类回应，老的未读、新的已读。</b>
     *
     * <p>全量合并会算「有未读」，若折叠时去读代表行（最新那条）的已读位则会算「已读」——
     * 那盏暖点被静默吞掉，不报错、不掉别的测试。</p>
     *
     * <p>水位模型下这个组合不可能出现（淹掉新的必然淹掉老的），所以今天安全；
     * 但那是<b>存储层的性质</b>，不是折叠本身的性质。这里直接给折叠喂一个非单调的判据，
     * 把「折叠不依赖已读怎么派生」钉死：谁哪天把已读改成逐行标记，这条会先红。</p>
     */
    @Test
    void collapseKeepsUnreadWhenAnOlderReactionIsUnread() {
        ReactionMark older = mark("remember:1", "remember", 100L);
        ReactionMark newer = mark("remember:2", "remember", 200L);
        // 非单调判据：只有「新的」算看过，老的没看过——水位模型下造不出来，逐行标记下随时会有
        java.util.function.LongPredicate onlyNewestSeen = t -> t == 200L;

        Map<String, EchoApi.CollapsedKind> out =
                EchoApi.collapseByKind(List.of(older, newer), onlyNewestSeen);

        assertThat(out).containsOnlyKeys("remember");
        assertThat(out.get("remember").newest().createdAt).isEqualTo(200L);
        assertThat(out.get("remember").allSeen())
                .as("老的那条还没被看见，整组就不能算看过——否则暖点被静默吞掉")
                .isFalse();
    }

    /** 反过来：整组确实都看过时才算看过。 */
    @Test
    void collapseReportsSeenOnlyWhenEveryReactionInTheGroupIsSeen() {
        ReactionMark older = mark("remember:1", "remember", 100L);
        ReactionMark newer = mark("remember:2", "remember", 200L);

        assertThat(EchoApi.collapseByKind(List.of(older, newer), t -> true).get("remember").allSeen())
                .isTrue();
        assertThat(EchoApi.collapseByKind(List.of(older, newer), t -> false).get("remember").allSeen())
                .isFalse();
    }

    /** 折叠按「类」分组，两类互不影响；每类各留自己最新的那条。 */
    @Test
    void collapseGroupsPerKindIndependently() {
        Map<String, EchoApi.CollapsedKind> out = EchoApi.collapseByKind(
                List.of(mark("r:1", "remember", 100L),
                        mark("f:1", "flower", 150L),
                        mark("r:2", "remember", 300L)),
                t -> t <= 150L);

        assertThat(out).containsOnlyKeys("remember", "flower");
        assertThat(out.get("remember").newest().createdAt).isEqualTo(300L);
        assertThat(out.get("remember").allSeen()).as("300 那条越过了水位").isFalse();
        assertThat(out.get("flower").newest().createdAt).isEqualTo(150L);
        assertThat(out.get("flower").allSeen()).isTrue();
    }

    private static ReactionMark mark(String id, String kind, long createdAt) {
        ReactionMark m = new ReactionMark();
        m.id = id;
        m.kind = kind;
        m.windowId = "1";
        m.actorAccountId = 42L;
        m.createdAt = createdAt;
        return m;
    }

    /** 同一次回应重复拉取要拿到同一个 id（前端按 id 去重）。 */
    @Test
    void markIdsAreStableAcrossCalls() throws Exception {
        long owner = guest("dev-owner");
        String win = createPet(owner, "麦麦");
        remember(guest("dev-a"), win);
        flower(guest("dev-b"), win);

        assertThat(arrivals(owner)).extracting(it -> it.get("id"))
                .isEqualTo(arrivals(owner).stream().map(it -> it.get("id")).toList());
    }

    /** 反复点「记得」不刷新时刻，否则同一次记得会在到达里一遍遍浮上来。 */
    @Test
    void repeatedRememberDoesNotRefreshTheMoment() throws Exception {
        long owner = guest("dev-owner");
        String win = createPet(owner, "麦麦");
        long actor = guest("dev-a");
        remember(actor, win);
        long first = (Long) arrivals(owner).get(0).get("createdAt");

        Thread.sleep(2);
        remember(actor, win);

        assertThat(arrivals(owner)).hasSize(1);
        assertThat((Long) arrivals(owner).get(0).get("createdAt")).isEqualTo(first);
    }
}
