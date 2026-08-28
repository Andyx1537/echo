package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.governance.BlockService;
import com.echo.http.governance.BlockStore;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.model.Models.LifeBookItem;
import com.echo.http.model.Models.PetProfile;
import com.echo.http.model.Models.Postcard;
import com.echo.http.model.Models.RelationEntry;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.http.visibility.ViewerRole;
import com.echo.http.visibility.VisibilityMatrix;
import com.echo.http.visibility.WindowBlock;
import com.echo.infra.llm.MockLlmClient;
import com.echo.infra.vision.StubVisionClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 🔴 <b>服务端按访客身份裁剪</b>：判据一律是「<b>响应体里有没有这个字段</b>」，
 * 不是「界面上有没有显示」（照 {@code TC-SEC-12} 的形态，见 {@code ACCEPTANCE.md}）。
 *
 * <p>这套用例存在的理由：此前「亲友视角已隐藏温度/明信片」靠的是前端布尔
 * {@code isFriendView} 不渲染，而服务端把整份主人视图发了出去。<b>没画出来不等于没发出去，
 * 抓一次包就全看得见</b>——所以下面每一条都去翻响应体，必要时把整个响应序列化成 JSON 全文搜。</p>
 */
class WindowVisibilityTrimTest {

    private static final long OWNER = 7001L;
    private static final long FRIEND = 7002L;
    private static final long STRANGER = 7003L;
    private static final long PRIVATE_OWNER = 7004L;

    /** 未解锁位的解锁提示：🔴 它泄露的是主人的行为度量（「你还差多久」），不是内容。 */
    private static final String LOCKED_HINT = "相伴满 100 天，会有一张冬天的明信片";
    private static final String UNLOCKED_CAPTION = "春天的风里，我们一起追蝴蝶";

    private InMemoryEchoStore store;
    private EchoApi api;
    private Router router;
    private BlockService blocks;
    private String windowId;
    private String privateWindowId;

    @BeforeEach
    void setUp() {
        IDGenerator ids = new IDGenerator(1L);
        store = new InMemoryEchoStore();
        api = new EchoApi(store, ids, new MockLlmClient(), new StubVisionClient(),
                null, null, false);
        blocks = new BlockService(new BlockStore(null), ids);
        api.setBlockService(blocks);
        router = api.routes(false);

        for (long id : new long[]{OWNER, FRIEND, STRANGER, PRIVATE_OWNER}) {
            AccountProfile p = new AccountProfile();
            p.accountId = id;
            p.deviceId = "dev-" + id;
            p.nickname = "u" + id;
            p.avatar = "grad-" + id;
            store.putProfile(p);
        }

        PetProfile pet = new PetProfile();
        pet.petId = "pet-" + OWNER;
        pet.ownerAccountId = OWNER;
        pet.name = "麦麦";
        pet.species = "金毛";
        pet.visibility = "public";
        pet.temperature = 88.0;
        pet.lifeBook.add(new LifeBookItem("第一次回家", 2019, "它用小鼻子闻了闻新家的味道"));
        store.putPet(pet);
        windowId = pet.petId;
        seedPostcards(windowId);

        PetProfile hidden = new PetProfile();
        hidden.petId = "pet-" + PRIVATE_OWNER;
        hidden.ownerAccountId = PRIVATE_OWNER;
        hidden.name = "橘子";
        hidden.species = "橘猫";
        hidden.visibility = "private";
        store.putPet(hidden);
        privateWindowId = hidden.petId;

        // 🔴 亲友关系是有方向的：isFriend(owner, viewer) 查的是 **owner 的**名单。
        //    所以 FRIEND 要拿到亲友档，必须由 OWNER 那一侧列出他。
        relate(OWNER, FRIEND);   // OWNER 认 FRIEND 是亲友 → FRIEND 对这扇窗是 FRIEND
        relate(FRIEND, OWNER);   // FRIEND 的名单里有 OWNER → 才会出现在 FRIEND 的 /relations 里
        relate(STRANGER, OWNER); // 🔴 单向：STRANGER 把 OWNER 加进自己名单，但 OWNER 没认他
    }

    private void seedPostcards(String petId) {
        List<Postcard> cards = new ArrayList<>();
        Postcard unlocked = new Postcard();
        unlocked.id = "pc-open";
        unlocked.petId = petId;
        unlocked.date = "2021-04-03";
        unlocked.caption = UNLOCKED_CAPTION;
        unlocked.locked = false;
        cards.add(unlocked);

        Postcard locked = new Postcard();
        locked.id = "pc-locked";
        locked.petId = petId;
        locked.date = "";
        locked.caption = "";
        locked.locked = true;
        locked.unlockHint = LOCKED_HINT;
        cards.add(locked);

        store.putPostcards(petId, cards);
    }

    private void relate(long me, long peer) {
        RelationEntry r = new RelationEntry();
        r.id = "rel-" + me + "-" + peer;
        r.accountId = me;
        r.peerAccountId = peer;
        r.peerName = "u" + peer;
        r.createdAt = System.currentTimeMillis();
        store.addRelation(r);
    }

    private Object call(String method, String path, long accountId) {
        Router.Match m = router.match(method, path);
        assertThat(m).as("route %s %s must match", method, path).isNotNull();
        RequestContext ctx = new RequestContext(method, m.pathParams, Map.of(),
                new JsonObject(), accountId);
        try {
            return m.entry.route.handle(ctx);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 从 {@code GET /relations} 里取出对 OWNER 那一条的 {@code pet} 子对象。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> relationPetView(long viewer) {
        Map<String, Object> page = (Map<String, Object>) call("GET", "/relations", viewer);
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        for (Map<String, Object> item : items) {
            if (("rel-" + viewer + "-" + OWNER).equals(item.get("id"))) {
                assertThat(item.get("viewableByMe")).as("这一条应当可见，否则测的就不是裁剪").isEqualTo(true);
                return (Map<String, Object>) item.get("pet");
            }
        }
        throw new AssertionError("没找到指向 OWNER 的那条亲友记录");
    }

    private String rawJson(Object body) {
        return new Gson().toJson(body);
    }

    // ==================================================== 未解锁位 · unlockHint

    /**
     * 🔴🔴 <b>这一条是按元素过滤数组最容易出的那个 bug 的专用哨兵。</b>
     *
     * <p>典型错法是「未解锁的格子前端不渲染」，但 {@code unlockHint} 仍然躺在响应体里。
     * 所以这里不只看有没有那张卡，而是把<b>整个响应序列化成 JSON 全文搜</b>——
     * 只要 {@code unlockHint} 这个键或那句提示的正文出现在任何一层，就算漏。</p>
     *
     * <p>为什么这一格值得单独一条：{@code unlockHint} 说的是「相伴满 100 天」「温度到 90°」，
     * 泄露的是<b>主人的行为度量</b>，而温度这个更弱的信号已被 {@code B5} 判为陌生人不出。</p>
     */
    @Test
    void unlockHintNeverReachesFriendOrStrangerAnywhereInTheBody() {
        for (long viewer : new long[]{FRIEND, STRANGER}) {
            Map<String, Object> pet = relationPetView(viewer);
            String json = rawJson(pet);

            assertThat(json)
                    .as("🔴 viewer=%s：unlockHint 这个键不许出现在响应体的任何一层", viewer)
                    .doesNotContain("unlockHint");
            assertThat(json)
                    .as("🔴 viewer=%s：解锁提示的正文也不许出现（换个键名藏着也算漏）", viewer)
                    .doesNotContain(LOCKED_HINT);
            assertThat(json)
                    .as("🔴 viewer=%s：未解锁位整个不下发，连 locked:true 的空壳都不给"
                            + "——空壳本身就在说「这里还有一张你没解锁的」", viewer)
                    .doesNotContain("pc-locked");
        }
    }

    /** 本人这一侧要照旧拿得到未解锁位与提示，否则「相伴解锁」这个玩法就没了。 */
    @Test
    @SuppressWarnings("unchecked")
    void ownerStillSeesLockedPostcardAndItsHint() {
        Map<String, Object> pet = (Map<String, Object>) call("GET", "/pet/me", OWNER);
        String json = rawJson(pet);
        assertThat(json).as("本人要能看到未解锁位").contains("pc-locked");
        assertThat(json).as("本人要能看到解锁提示").contains(LOCKED_HINT);
    }

    /**
     * {@code W11} 已解锁位对陌生人 —— 🔴 <b>暂定 A 案（可见），等截图确认。</b>
     *
     * <p>这一条钉的是「当前暂定值」，不是永久判据。翻
     * {@code VisibilityMatrix.POSTCARD_UNLOCKED_VISIBLE_TO_STRANGER} 时<b>本条会红</b>，
     * 那是预期的——连同这条一起改，不要绕过它。</p>
     */
    @Test
    void unlockedPostcardIsVisibleToStrangerUnderCurrentProvisionalRuling() {
        assertThat(VisibilityMatrix.visible(WindowBlock.POSTCARD_UNLOCKED, ViewerRole.STRANGER))
                .as("暂定 A 案：已解锁位与一张公开的回忆卡在敏感度上分不出来")
                .isTrue();

        String json = rawJson(relationPetView(STRANGER));
        assertThat(json).as("已解锁位的正文应当下发").contains(UNLOCKED_CAPTION);
    }

    // ==================================================== 温度 / 可见性设置

    /** {@code W13} 温度：亲友可见、陌生人不下发（不是不渲染）。 */
    @Test
    void temperatureGoesToFriendButNotToStranger() {
        assertThat(relationPetView(FRIEND))
                .as("亲友照旧可见（Q-12 未裁定前不作扩大解释）")
                .containsKey("temperature");
        assertThat(relationPetView(STRANGER))
                .as("🔴 陌生人：温度整个键不下发")
                .doesNotContainKey("temperature");
    }

    /** {@code W14} 可见性设置行：主人自己的配置，亲友与陌生人都不给。 */
    @Test
    void visibilitySettingIsOwnerOnly() {
        assertThat(relationPetView(FRIEND)).doesNotContainKey("visibility");
        assertThat(relationPetView(STRANGER)).doesNotContainKey("visibility");
        assertThat(call("GET", "/pet/me", OWNER)).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> mine = (Map<String, Object>) call("GET", "/pet/me", OWNER);
        assertThat(mine).as("本人要看得到自己的可见性设置").containsKey("visibility");
    }

    // ==================================================== 面孔墙

    /**
     * 🔴 <b>最要紧的一条</b>：{@code GET /windows/:id/remember} 此前连 {@code canView} 都没有——
     * 一扇 {@code private} 的窗，它的面孔墙任何人都能直接拉到，与页面渲染不渲染完全无关。
     */
    @Test
    void faceWallOfPrivateWindowIsNotReachableByOutsiders() {
        assertThatThrownBy(() -> call("GET", "/windows/" + privateWindowId + "/remember", STRANGER))
                .isInstanceOf(ApiException.class)
                .as("🔴 private 窗的面孔墙不该被外人直接拉到");

        assertThat(call("GET", "/windows/" + privateWindowId + "/remember", PRIVATE_OWNER))
                .as("主人自己照旧读得到").isInstanceOf(Map.class);
    }

    /** 看不见的窗也不能被「记得」，否则谁都能抬高 owner 的 rememberFacesCount。 */
    @Test
    void privateWindowCannotBeRememberedByOutsiders() {
        JsonObject body = new JsonObject();
        body.addProperty("remembered", true);
        Router.Match m = router.match("POST", "/windows/" + privateWindowId + "/remember");
        RequestContext ctx = new RequestContext("POST", m.pathParams, Map.of(), body, STRANGER);
        assertThatThrownBy(() -> m.entry.route.handle(ctx)).isInstanceOf(ApiException.class);
        assertThat(store.isRemembered(privateWindowId, STRANGER)).isFalse();
    }

    /**
     * {@code W3} {@code faces}：对非本人裁成空数组，且<b>任何身份都拿不到 {@code accountId}</b>。
     *
     * <p>那个 id 拿去调 {@code GET /users/:id} 就能换回昵称与粉丝数——「静默誓约」在网络层
     * 根本不匿名。前端拿它只为判断「这张脸是不是我」，一个布尔就够。</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void facesAreOwnerOnlyAndNeverCarryAccountId() {
        JsonObject body = new JsonObject();
        body.addProperty("remembered", true);
        Router.Match m = router.match("POST", "/windows/" + windowId + "/remember");
        try {
            m.entry.route.handle(new RequestContext("POST", m.pathParams, Map.of(), body, STRANGER));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(store.rememberFaces(windowId)).as("先确保墙上真的有一张脸").isNotEmpty();

        Map<String, Object> asStranger = (Map<String, Object>) call(
                "GET", "/windows/" + windowId + "/remember", STRANGER);
        assertThat((List<?>) asStranger.get("faces"))
                .as("🔴 非本人：faces 裁成空数组（键还在，M-3 要求它必含；空数组与「还没人记得」不可区分）")
                .isEmpty();
        assertThat(asStranger).as("暖光浓度对三档都可见").containsKey("warmthLevel");
        assertThat(asStranger).as("「我记得没」是访客自己的状态，永远可见").containsKey("meRemembered");

        Map<String, Object> asOwner = (Map<String, Object>) call(
                "GET", "/windows/" + windowId + "/remember", OWNER);
        List<Map<String, Object>> faces = (List<Map<String, Object>>) asOwner.get("faces");
        assertThat(faces).as("已裁定：本人保留可见").isNotEmpty();
        assertThat(faces.get(0)).containsKey("isMe").containsKey("avatar");
        assertThat(rawJson(asOwner))
                .as("🔴 连本人那一份也不下发第三方的 accountId")
                .doesNotContain("accountId");
    }

    // ==================================================== 拉黑挡内容

    /** 拉黑挡住了「人」，此前没挡住「内容」：被拉黑方照样能在瀑布上看到对方的公开窗。 */
    @Test
    @SuppressWarnings("unchecked")
    void plazaHidesWindowsOfBlockedCounterparties() {
        Map<String, Object> before = (Map<String, Object>) call("GET", "/plaza", STRANGER);
        assertThat(rawJson(before.get("items"))).contains(windowId);

        blocks.block(OWNER, STRANGER);

        Map<String, Object> after = (Map<String, Object>) call("GET", "/plaza", STRANGER);
        assertThat(rawJson(after.get("items")))
                .as("🔴 任一方向拉黑后，对方的公开窗不该再出现在我的瀑布上")
                .doesNotContain(windowId);
    }

    /** 详情页同理：只过 {@code canView} 会让被拉黑方照样点进去。 */
    @Test
    void windowDetailIsUnreachableAcrossABlock() {
        blocks.block(OWNER, STRANGER);
        assertThatThrownBy(() -> call("GET", "/windows/" + windowId, STRANGER))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> call("GET", "/windows/" + windowId + "/remember", STRANGER))
                .isInstanceOf(ApiException.class);
    }

    /** 被拉黑方的脚印不该累进 owner 的「N 次被看见」，否则与 {@code S8} 彻底不可见相悖。 */
    @Test
    void blockedPartyFootprintDoesNotRaiseSeenCount() {
        blocks.block(OWNER, STRANGER);
        long before = store.petById(windowId).seenCount;
        assertThat(call("POST", "/windows/" + windowId + "/seen", STRANGER))
                .as("🔴 回成功形状，不报错（报错就是一台拉黑探测器）")
                .isEqualTo(Map.of("ok", true));
        assertThat(store.petById(windowId).seenCount)
                .as("🔴 但不计数").isEqualTo(before);
    }

    // ==================================================== 矩阵本体

    /**
     * 白名单的<b>复式记账</b>：把 {@code RESEARCH-window-content-ownership §4.1} 的矩阵
     * 在测试里再抄一遍，两边不一致就红。
     *
     * <p>这条的用处不是「防止代码写错」，是<b>让改档位这件事必须改两个地方</b>——
     * 一处是配置、一处是对矩阵的复述。顺手改配置而没意识到自己在改可见性裁定，会在这里被拦下。</p>
     */
    @Test
    void matrixMatchesTheRuledVisibility() {
        assertRow(WindowBlock.LIFE_BOOK, ViewerRole.OWNER, ViewerRole.FRIEND, ViewerRole.STRANGER);
        assertRow(WindowBlock.WARMTH_LEVEL, ViewerRole.OWNER, ViewerRole.FRIEND, ViewerRole.STRANGER);
        assertRow(WindowBlock.ME_REMEMBERED, ViewerRole.OWNER, ViewerRole.FRIEND, ViewerRole.STRANGER);
        assertRow(WindowBlock.REMEMBER_BUTTON, ViewerRole.FRIEND, ViewerRole.STRANGER);
        assertRow(WindowBlock.FLOWER_BUTTON, ViewerRole.FRIEND, ViewerRole.STRANGER);
        assertRow(WindowBlock.TEMPERATURE, ViewerRole.OWNER, ViewerRole.FRIEND);
        // 暂定 A 案：陌生人可见。翻开关时这一行要跟着改。
        assertRow(WindowBlock.POSTCARD_UNLOCKED,
                ViewerRole.OWNER, ViewerRole.FRIEND, ViewerRole.STRANGER);
        assertRow(WindowBlock.POSTCARD_LOCKED, ViewerRole.OWNER);
        assertRow(WindowBlock.REMEMBER_FACES, ViewerRole.OWNER);
        assertRow(WindowBlock.VISIBILITY_SETTING, ViewerRole.OWNER);
        assertRow(WindowBlock.RECENT_ECHO_LIST, ViewerRole.OWNER);
        assertRow(WindowBlock.FLOWERS_RECEIVED, ViewerRole.OWNER);
        assertRow(WindowBlock.SEEN_COUNT, ViewerRole.OWNER);
    }

    private void assertRow(WindowBlock block, ViewerRole... allowed) {
        Set<ViewerRole> expect = allowed.length == 0
                ? EnumSet.noneOf(ViewerRole.class) : EnumSet.copyOf(List.of(allowed));
        for (ViewerRole role : ViewerRole.values()) {
            assertThat(VisibilityMatrix.visible(block, role))
                    .as("%s 对 %s", block, role)
                    .isEqualTo(expect.contains(role));
        }
    }
}
