package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.governance.BlockService;
import com.echo.http.governance.BlockStore;
import com.echo.http.governance.FollowStore;
import com.echo.http.model.Models.PetProfile;
import com.echo.http.store.EchoStore;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.infra.corpus.InMemoryTrainingCorpus;
import com.echo.infra.llm.MockLlmClient;
import com.echo.infra.vision.StubVisionClient;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 他人主页三端点：{@code GET /users/:id} · {@code GET /users/:id/windows} ·
 * {@code POST|DELETE /users/:id/follow}。
 *
 * <p>两条既有裁定在这里落地并被钉住：<b>粉丝数公开且精确但全站无榜</b>、
 * <b>拉黑自动解除双向关注且不可逆</b>。</p>
 */
class UserProfileFollowTest {

    private EchoStore store;
    private EchoApi api;
    private Router router;
    private FollowStore follows;
    private BlockService blocks;

    @BeforeEach
    void setUp() {
        store = new InMemoryEchoStore();
        IDGenerator ids = new IDGenerator(1L);
        api = new EchoApi(store, ids, new MockLlmClient(), new StubVisionClient(),
                null, new InMemoryTrainingCorpus());
        follows = new FollowStore(null);
        blocks = new BlockService(new BlockStore(null), ids);
        blocks.setFollowUnlinker(follows);
        api.setFollowStore(follows);
        api.setBlockService(blocks);
        router = api.routes(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String method, String path, long accountId) throws Exception {
        Router.Match m = router.match(method, path);
        assertThat(m).as("route %s %s must match", method, path).isNotNull();
        RequestContext ctx = new RequestContext(method, m.pathParams, Map.of(),
                new JsonObject(), accountId);
        return (Map<String, Object>) m.entry.route.handle(ctx);
    }

    private long guest(String deviceId) throws Exception {
        Router.Match m = router.match("POST", "/auth/guest");
        JsonObject b = new JsonObject();
        b.addProperty("deviceId", deviceId);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) m.entry.route.handle(
                new RequestContext("POST", m.pathParams, Map.of(), b, 0L));
        return Long.parseLong((String) out.get("accountId"));
    }

    /** 建一扇指定可见性的窗。 */
    private void giveWindow(long owner, String name, String visibility) {
        PetProfile pet = new PetProfile();
        pet.petId = String.valueOf(owner) + "0";
        pet.ownerAccountId = owner;
        pet.name = name;
        pet.visibility = visibility;
        pet.createTime = System.currentTimeMillis();
        store.putPet(pet);
    }

    // ------------------------------------------------------------ 主页

    @Test
    void profileCarriesNicknameAndFollowState() throws Exception {
        long author = guest("dev-author");
        long visitor = guest("dev-visitor");

        Map<String, Object> before = invoke("GET", "/users/" + author, visitor);
        assertThat(before.get("userId")).isEqualTo(String.valueOf(author));
        assertThat(before).containsKeys("nickname", "avatar", "isMe", "followerCount", "followingCount", "following");
        assertThat(before.get("isMe")).isEqualTo(false);
        assertThat(before.get("following")).isEqualTo(false);
        assertThat(before.get("followerCount")).isEqualTo(0);

        invoke("POST", "/users/" + author + "/follow", visitor);

        Map<String, Object> after = invoke("GET", "/users/" + author, visitor);
        assertThat(after.get("following")).isEqualTo(true);
        assertThat(after.get("followerCount")).isEqualTo(1);
    }

    /** 🔴 粉丝数公开且精确：给真数，不模糊化成「1000+」。 */
    @Test
    void followerCountIsExactAndPublic() throws Exception {
        long author = guest("dev-a");
        for (int i = 0; i < 7; i++) {
            invoke("POST", "/users/" + author + "/follow", guest("dev-fan-" + i));
        }
        // 陌生人看到的也是同一个精确数字——粉丝数是公开事实，不因看的人是谁而变
        long stranger = guest("dev-stranger");
        assertThat(invoke("GET", "/users/" + author, stranger).get("followerCount")).isEqualTo(7);
    }

    /**
     * 🔴 有粉丝数，但不许有「谁粉丝最多」。
     *
     * <p>这条守的是「全站不做最受欢迎作者榜」。榜单不是从零做起才叫榜单——
     * 只要存在一个按粉丝数排好序的出口，榜就已经在了，剩下的只是谁来渲染它。</p>
     *
     * <p>所以这里盯的是 {@link FollowStore} 的方法名：它可以回答「某个人有多少粉丝」
     * （{@code followerCount}），不可以回答「粉丝最多的是谁」。
     * 🔴 <b>不检查路由</b>——{@code /users/top} 本来就会命中 {@code /users/:id}，
     * 拿路由断言这件事只会得到一条永远为真的假用例。</p>
     */
    @Test
    void followStoreCannotAnswerWhoHasTheMostFollowers() {
        List<String> ranking = java.util.Arrays.stream(FollowStore.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(java.lang.reflect.Method::getName)
                .filter(n -> n.matches("(?i).*(top|popular|rank|hottest|leaderboard|most|trending).*"))
                .toList();

        assertThat(ranking)
                .as("全站不做「最受欢迎作者」榜：粉丝数是作者自己的事实，名次是平台替所有人排的")
                .isEmpty();
    }

    @Test
    void profileOfMyselfIsMarked() throws Exception {
        long me = guest("dev-me");
        assertThat(invoke("GET", "/users/" + me, me).get("isMe")).isEqualTo(true);
    }

    @Test
    void unknownUserIsNotFound() throws Exception {
        long me = guest("dev-x");
        assertThatThrownBy(() -> invoke("GET", "/users/99999", me))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.NOT_FOUND));
        assertThatThrownBy(() -> invoke("GET", "/users/not-a-number", me))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.BAD_PARAM));
    }

    // ---------------------------------------------------------- 他的窗

    @Test
    @SuppressWarnings("unchecked")
    void publicWindowShowsUpOnTheAuthorPage() throws Exception {
        long author = guest("dev-w-pub");
        long visitor = guest("dev-w-visitor");
        giveWindow(author, "麦麦", "public");

        Map<String, Object> out = invoke("GET", "/users/" + author + "/windows", visitor);
        assertThat(out).containsKeys("items", "nextCursor");
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("petName")).isEqualTo("麦麦");
    }

    /** 私密窗对别人不出现，但对窗主自己出现——复用 canView，不另写一套。 */
    @Test
    @SuppressWarnings("unchecked")
    void privateWindowIsHiddenFromOthersButVisibleToTheOwner() throws Exception {
        long author = guest("dev-w-priv");
        long visitor = guest("dev-w-priv-v");
        giveWindow(author, "布丁", "private");

        assertThat((List<Object>) invoke("GET", "/users/" + author + "/windows", visitor).get("items"))
                .isEmpty();
        assertThat((List<Object>) invoke("GET", "/users/" + author + "/windows", author).get("items"))
                .hasSize(1);
    }

    // ------------------------------------------------------------ 关注

    @Test
    void followIsIdempotentAndUnfollowBringsTheCountBackDown() throws Exception {
        long author = guest("dev-idem-a");
        long fan = guest("dev-idem-f");

        assertThat(invoke("POST", "/users/" + author + "/follow", fan)).isEqualTo(Map.of("following", true));
        assertThat(invoke("POST", "/users/" + author + "/follow", fan)).isEqualTo(Map.of("following", true));
        assertThat(follows.followerCount(author)).as("重复关注不产生第二条").isEqualTo(1);

        assertThat(invoke("DELETE", "/users/" + author + "/follow", fan)).isEqualTo(Map.of("following", false));
        assertThat(invoke("DELETE", "/users/" + author + "/follow", fan)).isEqualTo(Map.of("following", false));
        assertThat(follows.followerCount(author)).isZero();
    }

    @Test
    void cannotFollowYourself() throws Exception {
        long me = guest("dev-self");
        assertThatThrownBy(() -> invoke("POST", "/users/" + me + "/follow", me))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.BAD_PARAM));
    }

    // ------------------------------------------- 🔴 拉黑 × 关注（既有裁定）

    /** 🔴 拉黑自动解除<b>双向</b>关注。 */
    @Test
    void blockingUnlinksFollowsInBothDirections() throws Exception {
        long a = guest("dev-blk-a");
        long b = guest("dev-blk-b");
        invoke("POST", "/users/" + b + "/follow", a);
        invoke("POST", "/users/" + a + "/follow", b);
        assertThat(follows.isFollowing(a, b)).isTrue();
        assertThat(follows.isFollowing(b, a)).isTrue();

        blocks.block(a, b);

        assertThat(follows.isFollowing(a, b)).as("我拉黑了他，就不该还订阅着他").isFalse();
        assertThat(follows.isFollowing(b, a)).as("挡住互动却没挡住围观，等于没拉黑").isFalse();
    }

    /**
     * 🔴 解关注<b>不可逆</b>：解除拉黑不会把粉丝关系还回来。
     *
     * <p>代价是误拉黑再解除会让作者永久少一个粉丝，而粉丝数是精确公开的、作者看得出来。
     * 这个代价已被接受（见 {@code BlockService#block} 注释），所以这条用例钉的是
     * 「不要好心加一个自动恢复」。</p>
     */
    @Test
    void unblockingDoesNotRestoreTheFollow() throws Exception {
        long author = guest("dev-irr-a");
        long fan = guest("dev-irr-f");
        invoke("POST", "/users/" + author + "/follow", fan);

        blocks.block(author, fan);
        blocks.unblock(author, fan);

        assertThat(follows.isFollowing(fan, author)).isFalse();
        assertThat(follows.followerCount(author)).isZero();
    }

    /** 拉黑之后彼此的主页一律 404——不是空壳主页，也不是「你被拉黑了」。 */
    @Test
    void blockedPairCannotSeeEachOthersProfile() throws Exception {
        long a = guest("dev-hid-a");
        long b = guest("dev-hid-b");
        blocks.block(a, b);

        for (long[] pair : List.of(new long[]{a, b}, new long[]{b, a})) {
            assertThatThrownBy(() -> invoke("GET", "/users/" + pair[1], pair[0]))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.NOT_FOUND));
            assertThatThrownBy(() -> invoke("GET", "/users/" + pair[1] + "/windows", pair[0]))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiException.NOT_FOUND));
        }
    }

    /** 被拉黑方关注拉黑方：回执与成功完全一致，但不落库（与献花/记得同一套静默失效）。 */
    @Test
    void followFromABlockedAccountSilentlyDoesNothing() throws Exception {
        long author = guest("dev-sil-a");
        long fan = guest("dev-sil-f");
        blocks.block(author, fan);

        assertThat(invoke("POST", "/users/" + author + "/follow", fan))
                .as("回执必须与成功一致，否则拉黑就被暴露了")
                .isEqualTo(Map.of("following", true));
        assertThat(follows.followerCount(author)).as("但不落库").isZero();
    }
}
