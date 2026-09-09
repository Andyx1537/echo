package com.echo.http.onboarding;

import com.aengine.util.id.IDGenerator;
import com.echo.http.ApiException;
import com.echo.http.EchoApi;
import com.echo.http.HttpResult;
import com.echo.http.RequestContext;
import com.echo.http.Router;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.infra.corpus.InMemoryTrainingCorpus;
import com.echo.infra.llm.MockLlmClient;
import com.echo.infra.vision.StubVisionClient;
import com.echo.infra.vision.DetectSubject;
import com.echo.infra.vision.IVisionClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnboardingApiTest {
    private final IDGenerator ids = new IDGenerator(31);
    private final InMemoryEchoStore accounts = new InMemoryEchoStore();
    private final InMemoryOnboardingRepository repository = new InMemoryOnboardingRepository();
    private Router router;
    private long accountId;

    @BeforeEach
    void setUp() {
        accountId = ids.nextId();
        AccountProfile profile = new AccountProfile();
        profile.accountId = accountId;
        profile.deviceId = "onboarding-device";
        profile.guest = true;
        accounts.putProfile(profile);
        OnboardingGenerationPort generation = (jobId, anchor, adjustment, completion) -> {
            OnboardingAggregate.Candidate candidate = new OnboardingAggregate.Candidate();
            candidate.candidateId = String.valueOf(ids.nextId());
            candidate.gradient = "sunset";
            candidate.emoji = "🐾";
            candidate.signature = "从熟悉的日常，慢慢认出它";
            completion.accept(List.of(candidate), null);
        };
        OnboardingApi api = new OnboardingApi(repository,
                id -> !accounts.profile(id).guest,
                generation,
                new EchoOnboardingWindowPort(accounts, ids),
                new StubVisionClient(), ids);
        router = new Router();
        api.register(router);
    }

    @Test
    void fullFlowIsRecoverableAndConfirmationIsIdempotent() throws Exception {
        Map<String, Object> created = call("POST", "/pet/onboarding", body("petName", "麦麦"), "create-1");
        String onboardingId = (String) created.get("onboardingId");
        assertThat(created).containsEntry("petName", "麦麦").containsEntry("sessionVersion", 0L);

        newApi().attachUploadedAsset(accountId, onboardingId, "asset-1", 0,
                "resource-1", "image", "asset-fingerprint-1");
        OnboardingAggregate afterAsset = repository.find(onboardingId);
        String subjectId = afterAsset.subjects.getFirst().subjectId;
        JsonObject select = body("subjectId", subjectId, "expectedSessionVersion", 1);
        JsonObject crop = body("x", 0.1, "y", 0.1, "w", 0.7, "h", 0.7);
        select.add("crop", crop);
        call("POST", "/pet/onboarding/" + onboardingId + "/subject/select", select, "subject-1");

        saveAnswer(onboardingId, "Q1", 2, "home");
        saveAnswer(onboardingId, "Q2", 3, "tiny", "quiet");
        saveAnswer(onboardingId, "Q3", 4, "follows_me", "special_gesture");
        saveAnswer(onboardingId, "Q4", 5, "ordinary_routine");
        assertThat(repository.find(onboardingId).status).isEqualTo("ready_to_bind");

        accounts.profile(accountId).guest = false;
        call("POST", "/pet/onboarding/" + onboardingId + "/generate",
                body("expectedSessionVersion", 6), "generate-1");
        OnboardingAggregate generated = repository.find(onboardingId);
        assertThat(generated.status).isEqualTo("candidate_ready");
        assertThat(generated.candidates).hasSize(1);

        String candidateId = generated.candidates.getFirst().candidateId;
        call("POST", "/pet/onboarding/" + onboardingId + "/candidates/" + candidateId + "/select",
                body("expectedSessionVersion", generated.sessionVersion), "candidate-1");
        long consentExpected = repository.find(onboardingId).sessionVersion;
        call("PUT", "/pet/onboarding/" + onboardingId + "/consent",
                body("granted", true, "policyVersion", "memory-use-v1", "expectedSessionVersion", consentExpected),
                "consent-1");
        OnboardingAggregate ready = repository.find(onboardingId);
        assertThat(ready.status).isEqualTo("ready_to_confirm");

        JsonObject confirm = body("candidateId", candidateId, "consentVersion", ready.consent.consentVersion,
                "expectedSessionVersion", ready.sessionVersion);
        Map<String, Object> first = call("POST", "/pet/onboarding/" + onboardingId + "/confirm", confirm, "confirm-1");
        Map<String, Object> replay = call("POST", "/pet/onboarding/" + onboardingId + "/confirm", confirm, "confirm-1");
        assertThat(replay.get("petId")).isEqualTo(first.get("petId"));
        assertThat(accounts.petOfOwner(accountId)).isNotNull();
        assertThat(repository.find(onboardingId).status).isEqualTo("confirmed");
    }

    @Test
    void consentWithdrawalRemovesConfirmationCapabilityWithoutDeletingCandidates() throws Exception {
        String id = readyWithCandidate();
        OnboardingAggregate current = repository.find(id);
        String candidateId = current.candidates.getFirst().candidateId;
        call("POST", "/pet/onboarding/" + id + "/candidates/" + candidateId + "/select",
                body("expectedSessionVersion", current.sessionVersion), "select-c");
        current = repository.find(id);
        call("PUT", "/pet/onboarding/" + id + "/consent",
                body("granted", true, "policyVersion", "v1", "expectedSessionVersion", current.sessionVersion), "grant");
        current = repository.find(id);
        call("PUT", "/pet/onboarding/" + id + "/consent",
                body("granted", false, "policyVersion", "v1", "expectedSessionVersion", current.sessionVersion), "withdraw");
        current = repository.find(id);
        assertThat(current.status).isEqualTo("candidate_ready");
        assertThat(current.candidates).isNotEmpty();
        assertThat(OnboardingViews.snapshot(current).get("allowedActions").toString()).doesNotContain("confirm");
    }

    @Test
    void rejectsUnknownOptionAndStaleVersion() throws Exception {
        Map<String, Object> created = call("POST", "/pet/onboarding", new JsonObject(), "create-x");
        String id = (String) created.get("onboardingId");
        JsonObject bad = answer(0, "not-a-code");
        assertThatThrownBy(() -> call("PUT", "/pet/onboarding/" + id + "/answers/Q1", bad, "bad-answer"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.detail()).isEqualTo("answer_code_invalid"));

        JsonObject stale = body("petName", "新称呼", "expectedSessionVersion", 4);
        assertThatThrownBy(() -> call("PATCH", "/pet/onboarding/" + id + "/profile", stale, "stale"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.detail()).isEqualTo("onboarding_version_conflict");
                    assertThat(e.data()).containsKey("currentSnapshot");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailRestoresSubjectsCropAndPrivateAssetUrlWithoutLeakingResourceId() throws Exception {
        Map<String, Object> created = call("POST", "/pet/onboarding", new JsonObject(), "create-detail");
        String id = (String) created.get("onboardingId");
        newApi().attachUploadedAsset(accountId, id, "asset-detail", 0,
                "private-resource", "image", "asset-fingerprint-detail");
        OnboardingAggregate aggregate = repository.find(id);
        String subjectId = aggregate.subjects.getFirst().subjectId;
        JsonObject select = body("subjectId", subjectId, "expectedSessionVersion", 1);
        select.add("crop", body("x", 0.12, "y", 0.08, "w", 0.7, "h", 0.75));
        call("POST", "/pet/onboarding/" + id + "/subject/select", select, "select-detail");

        Map<String, Object> detail = call("GET", "/pet/onboarding/" + id, new JsonObject(), null);
        assertThat((List<?>) detail.get("subjectCandidates")).hasSize(1);
        assertThat(detail.get("selectedSubject")).isNotNull();
        assertThat(detail.get("crop")).isNotNull();
        Map<String, Object> asset = (Map<String, Object>) ((List<?>) detail.get("assets")).getFirst();
        assertThat(asset.get("url")).isEqualTo("/api/v1/pet/onboarding/" + id
                + "/assets/" + aggregate.assets.getFirst().assetId + "/content");
        assertThat(asset).doesNotContainKey("resourceId");
        assertThat(newApi().ownedAssetResource(accountId, id, aggregate.assets.getFirst().assetId))
                .isEqualTo("private-resource");
    }

    @Test
    void anonymousGenerationFailsClosedWithoutCreatingAJob() throws Exception {
        String id = readyToBind();
        OnboardingAggregate before = repository.find(id);
        assertThatThrownBy(() -> call("POST", "/pet/onboarding/" + id + "/generate",
                body("expectedSessionVersion", before.sessionVersion), "anonymous-generate"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("phone_binding_required"));
        OnboardingAggregate after = repository.find(id);
        assertThat(after.status).isEqualTo("ready_to_bind");
        assertThat(after.generationJob).isNull();
        assertThat(after.sessionVersion).isEqualTo(before.sessionVersion);
    }

    @Test
    void answerReplacementRetainsHistoryAndSupersedesItsFacts() throws Exception {
        String id = (String) call("POST", "/pet/onboarding", new JsonObject(), "create-history")
                .get("onboardingId");
        saveAnswer(id, "Q1", 0, "home");
        call("PUT", "/pet/onboarding/" + id + "/answers/Q1",
                answer(1, "adoption"), "replace-q1");
        OnboardingAggregate s = repository.find(id);
        assertThat(s.answers.get("Q1").answerCodes).containsExactly("adoption");
        assertThat(s.answerHistory).hasSize(1);
        assertThat(s.answerHistory.getFirst().answerCodes).containsExactly("home");
        assertThat(s.answerHistory.getFirst().replacedAt).isNotNull();
        assertThat(s.facts).filteredOn(fact -> fact.supersededAt == null)
                .extracting(fact -> fact.value).containsExactly("adoption");
        assertThat(s.facts).filteredOn(fact -> fact.supersededAt != null)
                .extracting(fact -> fact.value).containsExactly("home");
    }

    @Test
    void uploadReplayReturnsOriginalResultAndRejectsKeyReuseWithDifferentPayload() throws Exception {
        String id = (String) call("POST", "/pet/onboarding", new JsonObject(), "create-upload-replay")
                .get("onboardingId");
        OnboardingApi api = newApi();
        Map<String, Object> first = api.attachUploadedAsset(accountId, id, "asset-replay", 0,
                "resource-replay", "image", "fingerprint-a");
        Map<String, Object> replay = api.replayUploadedAsset(accountId, id, "asset-replay", "fingerprint-a");
        assertThat(replay).containsKeys("snapshot", "asset", "subjectCandidates", "quality");
        assertThat(((Map<?, ?>) replay.get("asset")).get("assetId"))
                .isEqualTo(((Map<?, ?>) first.get("asset")).get("assetId"));
        assertThat(repository.find(id).assets).hasSize(1);
        assertThatThrownBy(() -> api.replayUploadedAsset(accountId, id, "asset-replay", "fingerprint-b"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("idempotency_conflict"));
    }

    @Test
    void rejectsAnObviouslyDifferentPetAcrossAssets() throws Exception {
        String id = (String) call("POST", "/pet/onboarding", new JsonObject(), "create-consistency")
                .get("onboardingId");
        OnboardingApi api = newApi(speciesByResource());
        api.attachUploadedAsset(accountId, id, "dog-upload", 0,
                "dog-resource", "image", "dog-fingerprint");
        OnboardingAggregate first = repository.find(id);
        JsonObject select = body("subjectId", first.subjects.getFirst().subjectId,
                "expectedSessionVersion", 1);
        select.add("crop", body("x", 0.1, "y", 0.1, "w", 0.7, "h", 0.7));
        callWithApi(api, "POST", "/pet/onboarding/" + id + "/subject/select", select, "select-dog");

        assertThatThrownBy(() -> api.attachUploadedAsset(accountId, id, "cat-upload", 2,
                "cat-resource", "image", "cat-fingerprint"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("subject_inconsistent"));
        assertThat(repository.find(id).assets).hasSize(1);
    }

    @Test
    void unknownSecondAssetRequiresUserConfirmationAndJoinsTheChosenPetCluster() throws Exception {
        String id = (String) call("POST", "/pet/onboarding", new JsonObject(), "create-unknown")
                .get("onboardingId");
        OnboardingApi api = newApi(speciesByResource());
        api.attachUploadedAsset(accountId, id, "dog-upload-u", 0,
                "dog-resource", "image", "dog-fingerprint-u");
        OnboardingAggregate first = repository.find(id);
        String firstSubjectId = first.subjects.getFirst().subjectId;
        JsonObject firstSelect = body("subjectId", firstSubjectId, "expectedSessionVersion", 1);
        firstSelect.add("crop", body("x", 0.1, "y", 0.1, "w", 0.7, "h", 0.7));
        callWithApi(api, "POST", "/pet/onboarding/" + id + "/subject/select", firstSelect, "select-dog-u");

        api.attachUploadedAsset(accountId, id, "unknown-upload", 2,
                "unknown-resource", "image", "unknown-fingerprint");
        OnboardingAggregate pending = repository.find(id);
        assertThat(pending.assets.get(1).qualityState).isEqualTo("pending_identity_confirmation");
        String secondSubjectId = pending.subjects.stream()
                .filter(subject -> pending.assets.get(1).assetId.equals(subject.assetId))
                .findFirst().orElseThrow().subjectId;
        JsonObject secondSelect = body("subjectId", secondSubjectId, "expectedSessionVersion", 3);
        secondSelect.add("crop", body("x", 0.1, "y", 0.1, "w", 0.7, "h", 0.7));
        callWithApi(api, "POST", "/pet/onboarding/" + id + "/subject/select", secondSelect, "select-unknown");

        OnboardingAggregate confirmed = repository.find(id);
        OnboardingAggregate.Subject firstSubject = confirmed.subjects.stream()
                .filter(subject -> firstSubjectId.equals(subject.subjectId)).findFirst().orElseThrow();
        OnboardingAggregate.Subject secondSubject = confirmed.subjects.stream()
                .filter(subject -> secondSubjectId.equals(subject.subjectId)).findFirst().orElseThrow();
        assertThat(secondSubject.identityClusterId).isEqualTo(firstSubject.identityClusterId);
        assertThat(confirmed.assets.get(1).qualityState).isEqualTo("accepted");
    }

    @Test
    void legacyRoutesCanReturnEndpointRetired() throws Exception {
        String before = System.getProperty("echo.onboarding.legacy.enabled");
        try {
            System.setProperty("echo.onboarding.legacy.enabled", "false");
            EchoApi old = new EchoApi(accounts, ids, new MockLlmClient(), new StubVisionClient(),
                    null, new InMemoryTrainingCorpus());
            Router.Match route = old.routes().match("POST", "/pet/onboarding/start");
            assertThatThrownBy(() -> route.handle(new RequestContext("POST", route.pathParams,
                    Map.of(), new JsonObject(), accountId)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ApiException.GONE);
                        assertThat(e.detail()).isEqualTo("endpoint_retired");
                    });
        } finally {
            if (before == null) System.clearProperty("echo.onboarding.legacy.enabled");
            else System.setProperty("echo.onboarding.legacy.enabled", before);
        }
    }

    private String readyWithCandidate() throws Exception {
        String id = readyToBind();
        accounts.profile(accountId).guest = false;
        call("POST", "/pet/onboarding/" + id + "/generate",
                body("expectedSessionVersion", repository.find(id).sessionVersion), "generate-ready");
        assertThat(repository.find(id).generationJob).isNull();
        return id;
    }

    private String readyToBind() throws Exception {
        String id = (String) call("POST", "/pet/onboarding", new JsonObject(), "create-ready").get("onboardingId");
        newApi().attachUploadedAsset(accountId, id, "asset-ready", 0,
                "resource-ready", "image", "asset-fingerprint-ready");
        OnboardingAggregate s = repository.find(id);
        JsonObject select = body("subjectId", s.subjects.getFirst().subjectId, "expectedSessionVersion", 1);
        select.add("crop", body("x", 0.1, "y", 0.1, "w", 0.6, "h", 0.6));
        call("POST", "/pet/onboarding/" + id + "/subject/select", select, "subject-ready");
        saveAnswer(id, "Q1", 2, "home"); saveAnswer(id, "Q2", 3, "quiet");
        saveAnswer(id, "Q3", 4, "follows_me"); saveAnswer(id, "Q4", 5, "ordinary_routine");
        return id;
    }

    private OnboardingApi newApi() {
        return newApi(new StubVisionClient());
    }

    private OnboardingApi newApi(IVisionClient vision) {
        OnboardingGenerationPort generation = (jobId, anchor, adjustment, completion) -> {
            OnboardingAggregate.Candidate c = new OnboardingAggregate.Candidate();
            c.candidateId = String.valueOf(ids.nextId()); c.gradient = "sunset"; c.emoji = "🐾"; c.signature = "它";
            completion.accept(List.of(c), null);
        };
        return new OnboardingApi(repository, id -> !accounts.profile(id).guest, generation,
                new EchoOnboardingWindowPort(accounts, ids), vision, ids);
    }

    private static IVisionClient speciesByResource() {
        return resourceId -> List.of(DetectSubject.of(DetectSubject.SubjectType.ANIMAL,
                resourceId.startsWith("dog") ? "狗" : resourceId.startsWith("cat") ? "猫" : "其他", 0.9));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callWithApi(OnboardingApi api, String method, String path,
                                            JsonObject body, String key) throws Exception {
        Router local = new Router();
        api.register(local);
        Router.Match match = local.match(method, path);
        Object result = match.handle(new RequestContext(method, match.pathParams,
                Map.of(), body, accountId, Map.of("idempotency-key", key)));
        if (result instanceof HttpResult http) result = http.data();
        return (Map<String, Object>) result;
    }

    private void saveAnswer(String id, String q, long version, String... codes) throws Exception {
        call("PUT", "/pet/onboarding/" + id + "/answers/" + q, answer(version, codes), "answer-" + q);
    }

    private static JsonObject answer(long version, String... codes) {
        JsonObject body = body("answerVersion", "v1", "expectedSessionVersion", version);
        JsonArray values = new JsonArray();
        for (String code : codes) values.add(code);
        body.add("answerCodes", values);
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String method, String path, JsonObject body, String key) throws Exception {
        Router.Match match = router.match(method, path);
        assertThat(match).isNotNull();
        Map<String, String> headers = key == null ? Map.of() : Map.of("idempotency-key", key);
        Object result = match.handle(new RequestContext(method, match.pathParams,
                Map.of(), body, accountId, headers));
        if (result instanceof HttpResult http) result = http.data();
        return (Map<String, Object>) result;
    }

    private static JsonObject body(Object... pairs) {
        JsonObject body = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value instanceof Boolean b) body.addProperty(String.valueOf(pairs[i]), b);
            else if (value instanceof Number n) body.addProperty(String.valueOf(pairs[i]), n);
            else body.addProperty(String.valueOf(pairs[i]), String.valueOf(value));
        }
        return body;
    }
}
