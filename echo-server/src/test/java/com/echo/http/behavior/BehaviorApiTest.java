package com.echo.http.behavior;

import com.aengine.util.id.IDGenerator;
import com.echo.http.ApiException;
import com.echo.http.BehaviorApi;
import com.echo.http.RequestContext;
import com.echo.http.Router;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.store.InMemoryEchoStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BehaviorApiTest {
    private final IDGenerator ids = new IDGenerator(41);
    private final InMemoryEchoStore accounts = new InMemoryEchoStore();
    private final BehaviorEventStore store = new BehaviorEventStore(null);
    private final ExplicitFeedbackStore feedbacks = new ExplicitFeedbackStore(null);
    private BehaviorApi api;
    private Router router;
    private long guestId;
    private long boundId;

    @BeforeEach
    void setUp() {
        guestId = ids.nextId();
        boundId = ids.nextId();
        put(guestId, true, "游客");
        put(boundId, false, "路过的人");
        router = new Router();
        api = new BehaviorApi(store, accounts, ids, feedbacks, new BehaviorLedger(store, ids));
        api.register(router);
    }

    @Test
    void acceptsClientEventAndIgnoresClientAccount() throws Exception {
        JsonObject event = questionAnswered("k1");
        event.addProperty("accountId", 999);
        Map<String, Object> res = post(guestId, List.of(event));
        Map<String, Object> row = first(res);
        assertThat(row.get("status")).isEqualTo("accepted");
        assertThat(row.get("reasonCode")).isNull();
        BehaviorEvent saved = store.byIdempotency(guestId, "k1");
        assertThat(saved).isNotNull();
        assertThat(saved.accountId).isEqualTo(guestId);
        assertThat(saved.anonymousState).isEqualTo(BehaviorEvent.ANONYMOUS);
        assertThat(store.byIdempotency(999, "k1")).isNull();
    }

    @Test
    void rejectsUnknownNameForbiddenFieldAndServerEmitterWithoutFailingBatch() throws Exception {
        JsonObject ok = questionAnswered("ok");
        JsonObject unknown = questionAnswered("bad-name");
        unknown.addProperty("eventName", "user_is_sad");
        JsonObject forbidden = questionAnswered("phone");
        forbidden.getAsJsonObject("context").addProperty("phone", "13800000000");
        JsonObject serverOnly = questionAnswered("fav");
        serverOnly.addProperty("eventName", "work_favorite_changed");
        serverOnly.addProperty("surface", "work_detail");
        serverOnly.addProperty("targetType", "work");
        serverOnly.addProperty("purposeCode", "public_recommendation");
        serverOnly.add("context", new JsonObject());

        Map<String, Object> res = post(boundId, List.of(ok, unknown, forbidden, serverOnly));
        List<Map<String, Object>> rows = rows(res);
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).get("status")).isEqualTo("accepted");
        assertThat(rows.get(1).get("status")).isEqualTo("rejected");
        assertThat(rows.get(1).get("reasonCode")).isEqualTo(BehaviorDictionary.REJECT_UNKNOWN);
        assertThat(rows.get(2).get("reasonCode")).isEqualTo(BehaviorDictionary.REJECT_CONTEXT_FIELD);
        assertThat(rows.get(3).get("reasonCode")).isEqualTo(BehaviorDictionary.REJECT_EMITTER);
        assertThat(store.ofAccount(boundId)).hasSize(1);
    }

    @Test
    void duplicateIdempotencyDoesNotCreateSecondFact() throws Exception {
        JsonObject first = questionAnswered("same");
        JsonObject again = questionAnswered("same");
        Map<String, Object> a = post(boundId, List.of(first));
        Map<String, Object> b = post(boundId, List.of(again));
        assertThat(first(a).get("status")).isEqualTo("accepted");
        assertThat(first(b).get("status")).isEqualTo("duplicate");
        assertThat(first(b).get("eventId")).isEqualTo(first(a).get("eventId"));
        assertThat(store.ofAccount(boundId)).hasSize(1);
    }

    @Test
    void explicitFeedbackKeepsHistoryOnChange() throws Exception {
        Map<String, Object> first = postFeedback(boundId, "looks_like_it");
        assertThat(first.get("status")).isEqualTo("active");
        assertThat(first.get("supersedesId")).isNull();
        Map<String, Object> again = postFeedback(boundId, "looks_like_it");
        assertThat(again.get("feedbackId")).isEqualTo(first.get("feedbackId"));
        Map<String, Object> changed = postFeedback(boundId, "not_like_it");
        assertThat(changed.get("supersedesId")).isEqualTo(first.get("feedbackId"));
        assertThat(feedbacks.ofAccount(boundId)).hasSize(2);
        assertThat(feedbacks.active(boundId, "private_generation", "likeness", "gen-1").answerCode)
                .isEqualTo("not_like_it");
        assertThat(store.ofAccount(boundId))
                .extracting(e -> e.eventName)
                .contains("explicit_feedback_submitted", "explicit_feedback_changed");
    }

    @Test
    void unknownFeedbackIsRejectedAndBindingIsServerOnly() throws Exception {
        JsonObject bad = feedbackBody("looks_like_it");
        bad.addProperty("questionCode", "how_old");
        assertThatThrownBy(() -> call("POST", "/me/explicit-feedback", boundId, bad))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).detail())
                .isEqualTo(BehaviorDictionary.REJECT_UNKNOWN);
        api.ledger().bindingCompleted(boundId);
        api.ledger().bindingCompleted(boundId);
        assertThat(store.ofAccount(boundId)).extracting(e -> e.eventName)
                .containsOnly("onboarding_binding_completed");
    }

    @Test
    void missingAccountIsRejectedAsWholeRequest() {
        JsonObject event = questionAnswered("no-account");
        assertThatThrownBy(() -> post(0, List.of(event)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiException.UNAUTHORIZED);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postFeedback(long viewer, String answer) throws Exception {
        return (Map<String, Object>) call("POST", "/me/explicit-feedback", viewer, feedbackBody(answer));
    }

    private Object call(String method, String path, long viewer, JsonObject body) throws Exception {
        Router.Match match = router.match(method, path);
        assertThat(match).as("%s %s", method, path).isNotNull();
        return match.handle(new RequestContext(method, Map.of(), Map.of(), body, viewer, Map.of()));
    }

    private static JsonObject feedbackBody(String answer) {
        JsonObject body = new JsonObject();
        body.addProperty("scope", "private_generation");
        body.addProperty("targetType", "generation_result");
        body.addProperty("targetId", "gen-1");
        body.addProperty("questionCode", "likeness");
        body.addProperty("answerCode", answer);
        body.addProperty("answerVersion", 1);
        body.addProperty("sourceSurface", "first_generation");
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(long viewer, List<JsonObject> events) throws Exception {
        JsonObject body = new JsonObject();
        JsonArray arr = new JsonArray();
        events.forEach(arr::add);
        body.add("events", arr);
        Router.Match match = router.match("POST", "/behavior-events/batch");
        assertThat(match).isNotNull();
        return (Map<String, Object>) match.handle(
                new RequestContext("POST", Map.of(), Map.of(), body, viewer, Map.of()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> first(Map<String, Object> res) {
        return rows(res).get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> res) {
        return (List<Map<String, Object>>) res.get("results");
    }

    private void put(long accountId, boolean guest, String name) {
        AccountProfile p = new AccountProfile();
        p.accountId = accountId;
        p.deviceId = "dev-" + accountId;
        p.nickname = name;
        p.guest = guest;
        accounts.putProfile(p);
    }

    private static JsonObject questionAnswered(String key) {
        JsonObject context = new JsonObject();
        context.addProperty("questionId", "first_meeting_place");
        context.addProperty("questionVersion", 1);
        JsonArray answers = new JsonArray();
        answers.add("home");
        context.add("answerCodes", answers);
        JsonObject event = new JsonObject();
        event.addProperty("idempotencyKey", key);
        event.addProperty("eventName", "onboarding_question_answered");
        event.addProperty("sessionId", "sess-1");
        event.addProperty("surface", "private_onboarding");
        event.addProperty("targetType", "question");
        event.addProperty("targetId", "first_meeting_place:v1");
        event.addProperty("activeDurationMs", 4200);
        event.addProperty("occurredAt", "2026-09-02T00:00:00Z");
        event.addProperty("schemaVersion", 1);
        event.addProperty("purposeCode", "ui_adaptation");
        event.add("context", context);
        return event;
    }
}
