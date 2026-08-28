package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.model.Models;
import com.echo.http.model.Models.PetProfile;
import com.echo.http.model.Models.RelationEntry;
import com.echo.http.store.EchoStore;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.infra.corpus.InMemoryTrainingCorpus;
import com.echo.infra.llm.MockLlmClient;
import com.echo.infra.vision.DetectSubject;
import com.echo.infra.vision.StubVisionClient;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REST 网关六项定案的服务端强制单测（API-CONTRACT §12 验收口径）。
 *
 * <p>全内存态、外部服务 mock（MockLlmClient），不依赖 DB。直接经 {@link Router} 命中路由调用 handler。</p>
 */
class EchoApiTest {

    private EchoStore store;
    private EchoApi api;
    private Router router;

    @BeforeEach
    void setUp() {
        store = new InMemoryEchoStore();
        api = new EchoApi(store, new IDGenerator(1L), new MockLlmClient(), new StubVisionClient(),
                null, new InMemoryTrainingCorpus());
        // 开启 dev-only 路由（含 DELETE /pet/me resetPet，mi-2）供契约测试覆盖
        router = api.routes(true);
    }

    private Map<String, Object> invoke(String method, String path, long accountId, JsonObject body) throws Exception {
        return invoke(method, path, accountId, body, Map.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String method, String path, long accountId, JsonObject body,
                                       Map<String, String> query) throws Exception {
        Router.Match m = router.match(method, path);
        assertThat(m).as("route %s %s must match", method, path).isNotNull();
        RequestContext ctx = new RequestContext(method, m.pathParams, query,
                body == null ? new JsonObject() : body, accountId);
        Object out = m.entry.route.handle(ctx);
        return (Map<String, Object>) out;
    }

    private long guest(String deviceId) throws Exception {
        JsonObject b = new JsonObject();
        b.addProperty("deviceId", deviceId);
        Map<String, Object> data = invoke("POST", "/auth/guest", 0, b);
        return Long.parseLong((String) data.get("accountId"));
    }

    private String createPet(long accountId, String name) throws Exception {
        JsonObject start = new JsonObject();
        start.addProperty("petName", name);
        Map<String, Object> s = invoke("POST", "/pet/onboarding/start", accountId, start);
        String onboardingId = (String) s.get("onboardingId");

        JsonObject confirm = new JsonObject();
        confirm.addProperty("onboardingId", onboardingId);
        confirm.addProperty("finalCandidateId", "any");
        JsonObject scene = new JsonObject();
        scene.addProperty("caption", "相遇那天");
        scene.addProperty("allowUse", true);
        confirm.add("memoryScene", scene);
        Map<String, Object> c = invoke("POST", "/pet/onboarding/confirm", accountId, confirm);
        return (String) c.get("petId");
    }

    // ---------------------------------------------------------------- §1 游客

    @Test
    void guestIsIdempotentByDevice() throws Exception {
        long a1 = guest("dev-1");
        long a2 = guest("dev-1");
        long b1 = guest("dev-2");
        assertThat(a1).isEqualTo(a2);
        assertThat(b1).isNotEqualTo(a1);
    }

    // -------------------------------------------------- §2 建档护栏 + 定案 #1

    @Test
    void confirmRequiresMemorySceneAllowUse() throws Exception {
        long acc = guest("dev-onb");
        JsonObject start = new JsonObject();
        start.addProperty("petName", "麦麦");
        Map<String, Object> s = invoke("POST", "/pet/onboarding/start", acc, start);
        String onboardingId = (String) s.get("onboardingId");

        JsonObject confirm = new JsonObject();
        confirm.addProperty("onboardingId", onboardingId);
        confirm.addProperty("finalCandidateId", "any");
        JsonObject scene = new JsonObject();
        scene.addProperty("allowUse", false);
        confirm.add("memoryScene", scene);

        assertThatThrownBy(() -> invoke("POST", "/pet/onboarding/confirm", acc, confirm))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.RULE_FORBIDDEN));
    }

    // -------------------------------------------------- §2 肖像识别（/detect）

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> detectSubjects(long acc, String resourceId) throws Exception {
        JsonObject b = new JsonObject();
        b.addProperty("resourceId", resourceId);
        Map<String, Object> out = invoke("POST", "/pet/onboarding/detect", acc, b);
        assertThat(out).containsKey("subjects");
        return (List<Map<String, Object>>) out.get("subjects");
    }

    @Test
    void detectReturnsSingleNeutralSubject() throws Exception {
        long acc = guest("dev-detect");
        // 末位为字母 → 单主体中性默认
        List<Map<String, Object>> subjects = detectSubjects(acc, "res-abc");
        assertThat(subjects).hasSize(1);
        Map<String, Object> only = subjects.get(0);
        assertThat(only).containsKeys("subjectType", "species", "confidence");
        // 中性默认：绝不随机乱认（旧版随机 mock 的坑）
        assertThat(only.get("subjectType")).isEqualTo("animal");
        assertThat(only.get("species")).isEqualTo("狗");
        assertThat(((Number) only.get("confidence")).doubleValue()).isEqualTo(0.5);
        // 单主体可省略 box
        assertThat(only).doesNotContainKey("box");
    }

    @Test
    void detectStaysSingleRegardlessOfResourceId() throws Exception {
        long acc = guest("dev-detect-even");
        // 诚实版：桩看不到图，任何 resourceId（含末位偶数）都只返回单主体，绝不假装多主体
        List<Map<String, Object>> subjects = detectSubjects(acc, "res-42");
        assertThat(subjects).hasSize(1);
        assertThat(subjects.get(0).get("species")).isEqualTo("狗");
    }

    @Test
    void detectReturnsMultiSubjectsWhenDemoEnabled() {
        // 多主体仅在显式开启演示开关时出现（单元级验证桩逻辑）
        List<DetectSubject> subjects = new StubVisionClient(true).detect("res-abc");
        assertThat(subjects).hasSize(2);
        assertThat(subjects.get(0).confidence()).isGreaterThanOrEqualTo(subjects.get(1).confidence());
        assertThat(subjects.get(0).species()).isEqualTo("狗");
        assertThat(subjects.get(1).species()).isEqualTo("猫");
        assertThat(subjects.get(0).box()).isNotNull();
        assertThat(subjects.get(1).box()).isNotNull();
    }

    /** 诚实标识：桩的中性默认必须带 source=fallback，前端据此不把「狗」当识别结果展示。 */
    @Test
    void detectMarksStubResultAsFallbackSource() throws Exception {
        long acc = guest("dev-detect-source");
        JsonObject b = new JsonObject();
        b.addProperty("resourceId", "res-abc");
        Map<String, Object> out = invoke("POST", "/pet/onboarding/detect", acc, b);
        assertThat(out.get("source")).isEqualTo("fallback");
    }

    @Test
    void detectRequiresResourceId() throws Exception {
        long acc = guest("dev-detect-missing");
        assertThatThrownBy(() -> invoke("POST", "/pet/onboarding/detect", acc, new JsonObject()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.BAD_PARAM));
    }

    @Test
    void startAcceptsOptionalSubjectType() throws Exception {
        long acc = guest("dev-subject-type");
        JsonObject start = new JsonObject();
        start.addProperty("petName", "麦麦");
        start.addProperty("subjectType", "person");
        Map<String, Object> s = invoke("POST", "/pet/onboarding/start", acc, start);
        String onboardingId = (String) s.get("onboardingId");
        assertThat(store.onboarding(onboardingId).subjectType).isEqualTo("person");
    }

    @Test
    void startDefaultsSubjectTypeToAnimal() throws Exception {
        long acc = guest("dev-subject-default");
        JsonObject start = new JsonObject();
        start.addProperty("petName", "橘子");
        Map<String, Object> s = invoke("POST", "/pet/onboarding/start", acc, start);
        String onboardingId = (String) s.get("onboardingId");
        assertThat(store.onboarding(onboardingId).subjectType).isEqualTo("animal");
    }

    @Test
    void newPetDefaultsToPrivate() throws Exception {
        long acc = guest("dev-vis");
        createPet(acc, "麦麦");
        Map<String, Object> me = invoke("GET", "/pet/me", acc, null);
        assertThat(me.get("visibility")).isEqualTo("private");
    }

    // -------------------------------------------------- §5 献花额度（定案 #3）

    @Test
    void flowerQuotaEnforcedAndNoRankingField() throws Exception {
        long owner = guest("dev-owner");
        String windowId = createPet(owner, "橘子");
        long visitor = guest("dev-visitor");

        Map<String, Object> quota = invoke("GET", "/flowers/quota", visitor, null);
        assertThat(quota.get("dailyFree")).isEqualTo(5);
        assertThat(quota.get("remaining")).isEqualTo(5);

        JsonObject offer = new JsonObject();
        offer.addProperty("count", 3);
        Map<String, Object> r1 = invoke("POST", "/windows/" + windowId + "/flower", visitor, offer);
        assertThat(r1.get("ok")).isEqualTo(true);
        assertThat(r1).doesNotContainKey("rank");
        Map<?, ?> q1 = (Map<?, ?>) r1.get("quota");
        assertThat(q1.get("remaining")).isEqualTo(2);

        // 再献 3 朵，超出剩余 2 => 3001 额度不足
        JsonObject offer2 = new JsonObject();
        offer2.addProperty("count", 3);
        assertThatThrownBy(() -> invoke("POST", "/windows/" + windowId + "/flower", visitor, offer2))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.RULE_QUOTA_EXCEEDED));
    }

    // ---------------------------------------- §5 献花不写温度（定案 #5）

    @Test
    void flowerDoesNotChangeTemperature() throws Exception {
        long owner = guest("dev-t-owner");
        String windowId = createPet(owner, "毛毛");
        PetProfile pet = store.petById(windowId);
        double before = pet.temperature;

        long visitor = guest("dev-t-visitor");
        JsonObject offer = new JsonObject();
        offer.addProperty("count", 2);
        invoke("POST", "/windows/" + windowId + "/flower", visitor, offer);

        assertThat(store.petById(windowId).temperature).isEqualTo(before);
    }

    // ----------------------------------- §3 温度只由回访驱动（定案 #5 对偶）

    @Test
    void ownerVisitWarmsTemperature() throws Exception {
        long owner = guest("dev-visit");
        String windowId = createPet(owner, "点点");
        double before = store.petById(windowId).temperature;

        Map<String, Object> visit = invoke("POST", "/pet/me/visit", owner, null);
        double after = ((Number) visit.get("temperature")).doubleValue();
        assertThat(after).isGreaterThan(before);
        assertThat((List<?>) visit.get("newEchoes")).isNotEmpty();
    }

    // ------------------------------- §5 记得=暖光面孔墙/无数字（定案 #4）

    @Test
    void rememberWallHasNoExactCount() throws Exception {
        long owner = guest("dev-r-owner");
        String windowId = createPet(owner, "咪咪");
        long visitor = guest("dev-r-visitor");

        JsonObject b = new JsonObject();
        b.addProperty("remembered", true);
        Map<String, Object> set = invoke("POST", "/windows/" + windowId + "/remember", visitor, b);
        assertThat(set.get("remembered")).isEqualTo(true);

        // 幂等：再置 true 无副作用
        invoke("POST", "/windows/" + windowId + "/remember", visitor, b);

        Map<String, Object> wall = invoke("GET", "/windows/" + windowId + "/remember", visitor, null);
        assertThat(wall).containsKeys("warmthLevel", "faces", "meRemembered");
        assertThat(wall.get("meRemembered")).isEqualTo(true);
        // 红线：不返回精确总数/排名字段
        assertThat(wall).doesNotContainKeys("count", "total", "rememberCount", "rank");
    }

    // --------------------------- §6 看过仅 owner 内部可见（定案 #4）

    @Test
    void seenCountOnlyInOwnerInsights() throws Exception {
        long owner = guest("dev-seen");
        String windowId = createPet(owner, "旺财");

        long visitor = guest("dev-seen-v");
        invoke("POST", "/windows/" + windowId + "/seen", visitor, null);

        // 对外窗口详情不含看过数
        Map<String, Object> detail = invoke("GET", "/windows/" + windowId, owner, null);
        assertThat(detail).doesNotContainKey("seenCount");

        // owner 私域可见
        Map<String, Object> insights = invoke("GET", "/pet/me/insights", owner, null);
        assertThat(((Number) insights.get("seenCount")).longValue()).isGreaterThanOrEqualTo(1L);
    }

    // ------------------------- §7 明信片付费不可解锁内容（定案 #2）

    @Test
    void postcardContentCannotBePurchased() throws Exception {
        long owner = guest("dev-pc");
        String petId = createPet(owner, "布丁");
        // 取一张锁定的明信片
        Map<String, Object> list = invoke("GET", "/pet/me/postcards", owner, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) list.get("items");
        String lockedId = items.stream()
                .filter(c -> Boolean.TRUE.equals(c.get("locked")))
                .map(c -> (String) c.get("id"))
                .findFirst().orElseThrow();

        JsonObject paid = new JsonObject();
        paid.addProperty("paid", true);
        assertThatThrownBy(() -> invoke("POST", "/pet/me/postcards/" + lockedId + "/unlock", owner, paid))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.RULE_FORBIDDEN));
        assertThat(petId).isNotBlank();
    }

    // ------------------------- §7 明信片墙统一分页信封 {items,nextCursor}（M-8）

    @Test
    void postcardsReturnPagingEnvelope() throws Exception {
        long acc = guest("dev-pc-env");
        createPet(acc, "橘子");

        Map<String, Object> list = invoke("GET", "/pet/me/postcards", acc, null);
        // 与 records/messages 对齐：必含 items 与 nextCursor（缺一即三层不一致）
        assertThat(list).containsKeys("items", "nextCursor");
        assertThat((List<?>) list.get("items")).isNotEmpty();
    }

    /**
     * 🔴 明信片墙<b>升序</b>，最早的一张在最上面——产品有意为之的时间线叙事。
     *
     * <p>方向与 {@code /records}、{@code /messages} 的降序相反。这条用例存在的意义是：
     * 下一个觉得「三个列表方向不一致，统一一下」的人，会先看到它红。</p>
     */
    @Test
    void postcardsAreOrderedOldestFirstOnPurpose() {
        // 乱序写入，读出来必须是升序——不能靠"恰好按插入顺序存的"蒙对
        store.putPostcards("p1", List.of(
                postcard("c-late", 3000L),
                postcard("c-early", 1000L),
                postcard("c-mid", 2000L)));

        assertThat(store.postcards("p1").stream().map(c -> c.id).toList())
                .as("明信片墙从最早一张读起，不要改成降序")
                .containsExactly("c-early", "c-mid", "c-late");
    }

    /** 同刻两张用 id 兜平局：先后不能随底层 Map 的插入顺序变。 */
    @Test
    void postcardsBreakSameInstantTiesByIdSoTheOrderIsTotal() {
        store.putPostcards("p2", List.of(postcard("b", 1000L), postcard("a", 1000L)));

        assertThat(store.postcards("p2").stream().map(c -> c.id).toList())
                .containsExactly("a", "b");
    }

    private static Models.Postcard postcard(String id, long createdAt) {
        Models.Postcard c = new Models.Postcard();
        c.id = id;
        c.petId = "p";
        c.date = "2026-08-25";
        c.caption = "明信片";
        c.createdAt = createdAt;
        return c;
    }

    /**
     * 🔴 读不懂的游标要<b>报错</b>，不能安静地当成第一页。
     *
     * <p>安静回落到 0 时，客户端在「加载更多」语义下会把第一页反复追加，
     * 而两边都不会有任何错误——缺一页能看出来，无声地重复一页看不出来。</p>
     */
    @Test
    void malformedCursorFailsInsteadOfSilentlyRestartingFromTheFirstPage() throws Exception {
        long acc = guest("dev-cursor");
        createPet(acc, "芝麻");

        for (String path : List.of("/pet/me/postcards", "/records", "/messages")) {
            assertThatThrownBy(() -> invoke("GET", path, acc, null, Map.of("cursor", "not-a-number")))
                    .as("%s 收到坏游标应当报错", path)
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.BAD_PARAM));
        }
    }

    /** 但「没传」仍然走默认值——没要求和要求了没读懂是两件事。 */
    @Test
    void absentCursorStillFallsBackToTheDefault() throws Exception {
        long acc = guest("dev-cursor-absent");
        createPet(acc, "汤圆");

        assertThat(invoke("GET", "/records", acc, null, Map.of())).containsKey("items");
        assertThat(invoke("GET", "/records", acc, null, Map.of("cursor", ""))).containsKey("items");
    }

    // ------------------------- §6 窗口详情记得墙键名 rememberWall + faces（M-3）

    @Test
    @SuppressWarnings("unchecked")
    void windowDetailUsesRememberWallKeyWithFaces() throws Exception {
        long owner = guest("dev-wd-owner");
        String windowId = createPet(owner, "麦麦");
        // 公开窗口，游客可看
        PetProfile pet = store.petById(windowId);
        pet.visibility = "public";
        store.putPet(pet);

        long visitor = guest("dev-wd-visitor");
        JsonObject rem = new JsonObject();
        rem.addProperty("remembered", true);
        invoke("POST", "/windows/" + windowId + "/remember", visitor, rem);

        Map<String, Object> detail = invoke("GET", "/windows/" + windowId, visitor, null);
        // M-3：键名固定 rememberWall（不得再用 remember），且必含 faces
        assertThat(detail).containsKey("rememberWall");
        assertThat(detail).doesNotContainKey("remember");
        Map<String, Object> wall = (Map<String, Object>) detail.get("rememberWall");
        assertThat(wall).containsKeys("warmthLevel", "faces", "meRemembered");
        assertThat(wall.get("meRemembered")).isEqualTo(true);
        List<Map<String, Object>> faces = (List<Map<String, Object>>) wall.get("faces");
        assertThat(faces).isNotEmpty();
        assertThat(faces.get(0)).containsKeys("accountId", "avatar");
        // 红线：无精确总数/排名字段
        assertThat(wall).doesNotContainKeys("count", "total", "rememberCount", "rank");
    }

    // ------------------------- §6 窗口归属：ownerId 常驻 + isMine 与 flowerAllowed 分离

    @Test
    void windowCarriesOwnerIdAndIsMine() throws Exception {
        long owner = guest("dev-own-owner");
        String windowId = createPet(owner, "豆豆");
        PetProfile pet = store.petById(windowId);
        pet.visibility = "public";
        store.putPet(pet);

        Map<String, Object> mine = invoke("GET", "/windows/" + windowId, owner, null);
        assertThat(mine.get("ownerId")).isEqualTo(String.valueOf(owner));
        assertThat(mine.get("isMine")).isEqualTo(true);

        long visitor = guest("dev-own-visitor");
        Map<String, Object> theirs = invoke("GET", "/windows/" + windowId, visitor, null);
        assertThat(theirs.get("ownerId")).isEqualTo(String.valueOf(owner));
        assertThat(theirs.get("isMine")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void plazaCardsCarryOwnerIdForAuthorNavigation() throws Exception {
        long owner = guest("dev-plaza-own");
        String windowId = createPet(owner, "花卷");
        PetProfile pet = store.petById(windowId);
        pet.visibility = "public";
        store.putPet(pet);

        Map<String, Object> page = invoke("GET", "/plaza", guest("dev-plaza-viewer"), null);
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        Map<String, Object> card = items.stream()
                .filter(w -> windowId.equals(w.get("id")))
                .findFirst().orElseThrow();
        // 昵称不唯一，前端不能按昵称反查作者，所以列表页的卡也必须带 ownerId
        assertThat(card.get("ownerId")).isEqualTo(String.valueOf(owner));
    }

    // ------------------------- §8 亲友字段：lastActive/reels/viewableByMe/pet(MyPet)（M-5）

    private void addRelation(long me, long peer, String name, long lastActiveAt) {
        RelationEntry rel = new RelationEntry();
        rel.id = String.valueOf(peer); // 测试内用 peer 作稳定 id
        rel.accountId = me;
        rel.peerAccountId = peer;
        rel.peerName = name;
        rel.online = true;
        rel.lastActiveAt = lastActiveAt;
        rel.createdAt = System.currentTimeMillis();
        store.addRelation(rel);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> relationByName(Map<String, Object> relations, String name) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) relations.get("items");
        return items.stream().filter(r -> name.equals(r.get("name"))).findFirst().orElseThrow();
    }

    @Test
    @SuppressWarnings("unchecked")
    void relationsCarryLastActiveReelsAndMyPetShapeWhenViewable() throws Exception {
        long me = guest("dev-rel-me");
        long peerPub = guest("dev-rel-pub");
        String pubPetId = createPet(peerPub, "公开宠");
        PetProfile pubPet = store.petById(pubPetId);
        pubPet.visibility = "public"; // 公开 → 我有权查看
        store.putPet(pubPet);

        long lastActive = 1_700_000_000_000L;
        addRelation(me, peerPub, "远山", lastActive);

        Map<String, Object> relations = invoke("GET", "/relations", me, null);
        assertThat(relations).containsKey("items");
        Map<String, Object> pub = relationByName(relations, "远山");

        // lastActive 为毫秒时间戳
        assertThat(((Number) pub.get("lastActive")).longValue()).isEqualTo(lastActive);
        // 可见 → viewableByMe=true，reels 真实（建档已铺一条近况），pet 为 MyPet 形状
        assertThat(pub.get("viewableByMe")).isEqualTo(true);
        List<Map<String, Object>> reels = (List<Map<String, Object>>) pub.get("reels");
        assertThat(reels).isNotEmpty();
        assertThat(reels.get(0)).containsKeys("id", "text", "createdAt", "placeholder");
        Map<String, Object> pet = (Map<String, Object>) pub.get("pet");
        // MyPet 形状（非 Window）：含 petId/temperature/postcards/lifeBook/recent；不含 Window 的 petName/ownerName/span
        assertThat(pet).containsKeys("petId", "name", "temperature", "visibility", "cover", "postcards", "lifeBook", "recent");
        assertThat(pet).doesNotContainKeys("petName", "ownerName", "span");
    }

    @Test
    @SuppressWarnings("unchecked")
    void relationsHidesReelsAndPetForNonViewablePeer() throws Exception {
        long me = guest("dev-rel-me2");
        long peerPriv = guest("dev-rel-priv");
        createPet(peerPriv, "私密宠"); // 默认 private → 我无权查看
        addRelation(me, peerPriv, "隐者", 1_700_000_000_000L);

        Map<String, Object> relations = invoke("GET", "/relations", me, null);
        Map<String, Object> priv = relationByName(relations, "隐者");
        // TC-08：无权查看者不下发 reels/动态与宠物主页
        assertThat(priv.get("viewableByMe")).isEqualTo(false);
        assertThat((List<Map<String, Object>>) priv.get("reels")).isEmpty();
        assertThat(priv.get("hasUnseenReel")).isEqualTo(false);
        assertThat(priv.get("pet")).isNull();
    }

    @Test
    void relationsViewableWhenPeerListsMeAsFriend() throws Exception {
        long me = guest("dev-rel-me3");
        long peer = guest("dev-rel-friend");
        String friendPetId = createPet(peer, "挚友宠");
        PetProfile friendPet = store.petById(friendPetId);
        friendPet.visibility = "friends"; // 挚友可见
        store.putPet(friendPet);
        // 对方（peer）把"我"列为亲友（反向关系）→ canView friends 分支成立
        addRelation(peer, me, "我在对方名单", 0L);
        // 我这边也有到对方的关系
        addRelation(me, peer, "挚友", 1_700_000_000_000L);

        Map<String, Object> relations = invoke("GET", "/relations", me, null);
        Map<String, Object> friend = relationByName(relations, "挚友");
        assertThat(friend.get("viewableByMe")).isEqualTo(true);
    }

    // ------------------------- §9/§10 分页信封统一 {items,nextCursor}（M-1/M-2）

    @Test
    void recordsReturnPagingEnvelope() throws Exception {
        long acc = guest("dev-rec");
        JsonObject body = new JsonObject();
        body.addProperty("scope", "self");
        body.addProperty("text", "今天想起了你");
        invoke("POST", "/records", acc, body);

        Map<String, Object> list = invoke("GET", "/records", acc, null);
        assertThat(list).containsKeys("items", "nextCursor");
        assertThat((List<?>) list.get("items")).isNotEmpty();
    }

    @Test
    void messagesReturnPagingEnvelope() throws Exception {
        long acc = guest("dev-msg");
        com.echo.http.model.Models.MessageEntry m = new com.echo.http.model.Models.MessageEntry();
        m.id = "1";
        m.accountId = acc;
        m.kind = "system";
        m.title = "系统消息";
        m.preview = "预览";
        m.createdAt = System.currentTimeMillis();
        m.routeType = "window";
        m.routeId = "w1";
        store.addMessage(m);

        Map<String, Object> list = invoke("GET", "/messages", acc, null);
        assertThat(list).containsKeys("items", "nextCursor");
        assertThat((List<?>) list.get("items")).isNotEmpty();
    }

    // ------------------------- mi-2 dev-only DELETE /pet/me（resetPet）

    @Test
    void resetPetClearsPetAndReturnsToPreCreateState() throws Exception {
        long acc = guest("dev-reset");
        createPet(acc, "橘子");
        Map<String, Object> me1 = invoke("GET", "/me", acc, null);
        assertThat(me1.get("hasPet")).isEqualTo(true);

        Map<String, Object> del = invoke("DELETE", "/pet/me", acc, null);
        assertThat(del.get("ok")).isEqualTo(true);

        Map<String, Object> me2 = invoke("GET", "/me", acc, null);
        assertThat(me2.get("hasPet")).isEqualTo(false);
        // 宠物已删除：再取 /pet/me 应 404
        assertThatThrownBy(() -> invoke("GET", "/pet/me", acc, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.NOT_FOUND));
    }

    @Test
    void resetPetRouteAbsentWhenDevRoutesDisabled() {
        // 生产装配（devRoutes=false）不应挂载 DELETE /pet/me
        Router prod = api.routes(false);
        assertThat(prod.match("DELETE", "/pet/me")).isNull();
    }
}
