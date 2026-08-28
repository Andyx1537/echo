package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.store.EchoStore;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.infra.corpus.InMemoryTrainingCorpus;
import com.echo.infra.corpus.TrainSample;
import com.echo.infra.llm.ILlmClient;
import com.echo.infra.llm.MockLlmClient;
import com.echo.infra.vision.StubVisionClient;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 训练语料回流（PIPL 门控）与生成路径 + CopyGuard 过滤单测。全内存态、LLM mock，不发起真实网络。
 */
class TrainingReflowTest {

    private EchoStore store;
    private InMemoryTrainingCorpus corpus;

    @BeforeEach
    void setUp() {
        store = new InMemoryEchoStore();
        corpus = new InMemoryTrainingCorpus();
    }

    private Router routerWith(ILlmClient llm) {
        EchoApi api = new EchoApi(store, new IDGenerator(1L), llm, new StubVisionClient(), null, corpus);
        return api.routes();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(Router router, String method, String path, long accountId, JsonObject body)
            throws Exception {
        Router.Match m = router.match(method, path);
        assertThat(m).as("route %s %s must match", method, path).isNotNull();
        RequestContext ctx = new RequestContext(method, m.pathParams, Map.of(),
                body == null ? new JsonObject() : body, accountId);
        return (Map<String, Object>) m.entry.route.handle(ctx);
    }

    private long guest(Router router, String deviceId) throws Exception {
        JsonObject b = new JsonObject();
        b.addProperty("deviceId", deviceId);
        Map<String, Object> data = invoke(router, "POST", "/auth/guest", 0, b);
        return Long.parseLong((String) data.get("accountId"));
    }

    private String createPet(Router router, long accountId, String name, boolean trainConsent) throws Exception {
        JsonObject start = new JsonObject();
        start.addProperty("petName", name);
        start.addProperty("trainConsent", trainConsent);
        start.addProperty("resourceId", "res-abc");
        start.addProperty("detectedSpecies", "狗");
        Map<String, Object> s = invoke(router, "POST", "/pet/onboarding/start", accountId, start);
        String onboardingId = (String) s.get("onboardingId");

        JsonObject confirm = new JsonObject();
        confirm.addProperty("onboardingId", onboardingId);
        confirm.addProperty("finalCandidateId", "any");
        JsonObject scene = new JsonObject();
        scene.addProperty("caption", "相遇那天");
        scene.addProperty("allowUse", true);
        confirm.add("memoryScene", scene);
        Map<String, Object> c = invoke(router, "POST", "/pet/onboarding/confirm", accountId, confirm);
        return (String) c.get("petId");
    }

    // ------------------------------------------------------ 任务二：consent 门控

    @Test
    void consentTrueWritesCorpus() throws Exception {
        Router router = routerWith(new MockLlmClient());
        long acc = guest(router, "dev-consent-yes");
        String petId = createPet(router, acc, "麦麦", true);

        assertThat(corpus.size()).isEqualTo(1);
        TrainSample s = corpus.byPet(petId).get(0);
        assertThat(s.consent).isTrue();
        assertThat(s.petId).isEqualTo(petId);
        assertThat(s.correctedSpecies).isEqualTo("毛孩子"); // start 未传 species → 默认
        assertThat(s.inputRefs).contains("res-abc");
        assertThat(s.detectedSpecies).isEqualTo("狗");
        // 去标识：不落真实 accountId
        assertThat(s.accountId).startsWith("acct_");
        assertThat(s.accountId).doesNotContain(String.valueOf(acc));
    }

    @Test
    void consentFalseDoesNotWriteCorpus() throws Exception {
        Router router = routerWith(new MockLlmClient());
        long acc = guest(router, "dev-consent-no");
        createPet(router, acc, "橘子", false);

        assertThat(corpus.size()).isZero();
    }

    @Test
    void corpusRejectsSampleWithoutConsent() {
        TrainSample s = new TrainSample();
        s.petId = "p1";
        s.consent = false;
        assertThat(corpus.write(s)).isFalse();
        assertThat(corpus.size()).isZero();
    }

    // ------------------------------------------------ 任务一：生成路径 + CopyGuard

    @Test
    void mockGenerationPathProducesGentleEcho() throws Exception {
        // provider=mock 的生成路径：complete 返回 JSON → 回落温柔文案池，且过 CopyGuard
        Router router = routerWith(new MockLlmClient());
        long owner = guest(router, "dev-gen-mock");
        createPet(router, owner, "点点", false);

        Map<String, Object> visit = invoke(router, "POST", "/pet/me/visit", owner, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> echoes = (List<Map<String, Object>>) visit.get("newEchoes");
        assertThat(echoes).isNotEmpty();
        String text = (String) echoes.get(0).get("text");
        assertThat(text).isNotBlank();
        assertThat(CopyGuardFilter.hasBanned(text)).isFalse();
    }

    @Test
    void generatedTextIsSanitizedByCopyGuard() throws Exception {
        // 若 LLM 吐出含禁用词的文本，返回前必须被 CopyGuard 改写（定案 #6）
        ILlmClient bannedLlm = new ILlmClient() {
            @Override
            public String enrich(String rawPrefs) {
                return rawPrefs;
            }

            @Override
            public String complete(String prompt) {
                return "它今天很好，只是它已经去世了，永别了。";
            }
        };
        Router router = routerWith(bannedLlm);
        long owner = guest(router, "dev-gen-banned");
        createPet(router, owner, "毛毛", false);

        Map<String, Object> visit = invoke(router, "POST", "/pet/me/visit", owner, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> echoes = (List<Map<String, Object>>) visit.get("newEchoes");
        String text = (String) echoes.get(0).get("text");
        assertThat(text).doesNotContain("去世");
        assertThat(text).doesNotContain("永别");
        assertThat(CopyGuardFilter.hasBanned(text)).isFalse();
    }

    @Test
    void echoReplyFeedbackReflowGatedByConsent() throws Exception {
        Router router = routerWith(new MockLlmClient());
        long owner = guest(router, "dev-reply-consent");
        String petId = createPet(router, owner, "布丁", true);
        int afterConfirm = corpus.size();

        // 取一条 echo 回信 → 触发 feedback 样本（consent=true 应写入）
        List<?> echoes = store.echoesOfPet(petId);
        assertThat(echoes).isNotEmpty();
        String echoId = firstEchoId(petId);
        JsonObject reply = new JsonObject();
        reply.addProperty("text", "我很想你");
        invoke(router, "POST", "/pet/me/echoes/" + echoId + "/reply", owner, reply);

        assertThat(corpus.size()).isEqualTo(afterConfirm + 1);
    }

    private String firstEchoId(String petId) {
        return store.echoesOfPet(petId).get(0).echoId;
    }
}
