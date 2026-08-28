package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.governance.BlockService;
import com.echo.http.governance.BlockStore;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.model.Models.PetProfile;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.infra.llm.MockLlmClient;
import com.echo.infra.vision.StubVisionClient;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 拉黑的<b>静默失效</b>在真实互动端点上的行为（献花 / 记得）。
 *
 * <p>本套用例的价值不在"拦住了"，而在<b>拦住的方式对被拉黑方不可察觉</b>：
 * 响应必须与成功完全同形，且服务端确实没有落库。这两条缺一条都不算做对——</p>
 * <ul>
 *   <li>只做"不落库"而返回错误 → 一次拉黑变成一次冲突升级；</li>
 *   <li>只做"返回成功"而仍落库 → 拉黑等于没有。</li>
 * </ul>
 */
class BlockSilentFailureTest {

    private static final long AUTHOR = 5001L;
    private static final long BLOCKED = 5002L;
    private static final long NORMAL = 5003L;

    private InMemoryEchoStore store;
    private EchoApi api;
    private Router router;
    private BlockService blocks;
    private String windowId;

    @BeforeEach
    void setUp() {
        IDGenerator ids = new IDGenerator(1L);
        store = new InMemoryEchoStore();
        api = new EchoApi(store, ids, new MockLlmClient(), new StubVisionClient(),
                null, null, false);
        blocks = new BlockService(new BlockStore(null), ids);
        api.setBlockService(blocks);
        router = api.routes(false);

        for (long id : new long[]{AUTHOR, BLOCKED, NORMAL}) {
            AccountProfile p = new AccountProfile();
            p.accountId = id;
            p.deviceId = "dev-" + id;
            p.nickname = "u" + id;
            store.putProfile(p);
        }
        // 作者的公开窗口
        PetProfile pet = new PetProfile();
        pet.petId = "pet-" + AUTHOR;
        pet.ownerAccountId = AUTHOR;
        pet.name = "麦麦";
        pet.species = "金毛";
        pet.visibility = "public";
        store.putPet(pet);
        windowId = pet.petId;
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

    /**
     * 🔴 献花：被拉黑方拿到成功响应，但花没有落库。
     *
     * <p>同时断言响应<b>与正常用户同形</b>（同样的 key 集合）——形状不同也是一条泄漏路径。</p>
     */
    @Test
    void flowerFromBlockedPartyLooksSuccessfulButDoesNotLand() {
        JsonObject body = new JsonObject();
        body.addProperty("count", 1);

        @SuppressWarnings("unchecked")
        Map<String, Object> normal = (Map<String, Object>) call(
                "POST", "/windows/" + windowId + "/flower", NORMAL, body);
        long afterNormal = store.petById(windowId).flowersReceived;
        assertThat(afterNormal).as("正常用户的花要落库").isEqualTo(1);

        blocks.block(AUTHOR, BLOCKED);

        @SuppressWarnings("unchecked")
        Map<String, Object> blocked = (Map<String, Object>) call(
                "POST", "/windows/" + windowId + "/flower", BLOCKED, body);

        assertThat(blocked.get("ok")).as("🔴 必须看起来成功").isEqualTo(true);
        assertThat(blocked.keySet())
                .as("🔴 响应形状必须与正常用户一致，否则形状差异就是拉黑探测器")
                .isEqualTo(normal.keySet());
        assertThat(store.petById(windowId).flowersReceived)
                .as("🔴 但实际不生效：收花数不变").isEqualTo(afterNormal);
    }

    /**
     * 🔴 记得：被拉黑方回显他<b>请求的那个值</b>，而不是真实状态。
     *
     * <p>如果回真实状态（false），就与他刚提交的 true 不符，等于把拉黑说出来了。</p>
     */
    @Test
    void rememberFromBlockedPartyEchoesRequestedValueNotTruth() {
        blocks.block(AUTHOR, BLOCKED);
        JsonObject body = new JsonObject();
        body.addProperty("remembered", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) call(
                "POST", "/windows/" + windowId + "/remember", BLOCKED, body);

        assertThat(out.get("remembered")).as("🔴 回显请求值，看起来成功").isEqualTo(true);
        assertThat(store.isRemembered(windowId, BLOCKED))
                .as("🔴 实际没有落库").isFalse();
        assertThat(store.rememberFaces(windowId))
                .as("🔴 也不进暖光面孔墙").doesNotContain(BLOCKED);
    }

    /** 未被拉黑的人一切正常——拉黑是单向且定向的，不能牵连别人。 */
    @Test
    void normalUserUnaffectedByOthersBlock() {
        blocks.block(AUTHOR, BLOCKED);
        JsonObject body = new JsonObject();
        body.addProperty("remembered", true);
        call("POST", "/windows/" + windowId + "/remember", NORMAL, body);
        assertThat(store.isRemembered(windowId, NORMAL)).isTrue();
    }

    /** 解除拉黑后互动恢复生效。 */
    @Test
    void interactionResumesAfterUnblock() {
        blocks.block(AUTHOR, BLOCKED);
        JsonObject body = new JsonObject();
        body.addProperty("remembered", true);
        call("POST", "/windows/" + windowId + "/remember", BLOCKED, body);
        assertThat(store.isRemembered(windowId, BLOCKED)).isFalse();

        blocks.unblock(AUTHOR, BLOCKED);
        call("POST", "/windows/" + windowId + "/remember", BLOCKED, body);
        assertThat(store.isRemembered(windowId, BLOCKED)).isTrue();
    }

    /** 🔴 反方向不受影响：被拉黑方的窗口，拉黑方仍能正常互动（单向语义）。 */
    @Test
    void blockDoesNotBlockTheReverseDirection() {
        PetProfile theirs = new PetProfile();
        theirs.petId = "pet-" + BLOCKED;
        theirs.ownerAccountId = BLOCKED;
        theirs.name = "橘子";
        theirs.species = "橘猫";
        theirs.visibility = "public";
        store.putPet(theirs);

        blocks.block(AUTHOR, BLOCKED);
        JsonObject body = new JsonObject();
        body.addProperty("remembered", true);
        call("POST", "/windows/" + theirs.petId + "/remember", AUTHOR, body);
        assertThat(store.isRemembered(theirs.petId, AUTHOR))
                .as("我拉黑了他，不代表我不能看他的窗口").isTrue();
    }
}
