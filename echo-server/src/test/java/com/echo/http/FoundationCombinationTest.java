package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.behavior.BehaviorDictionary;
import com.echo.http.behavior.BehaviorEventStore;
import com.echo.http.behavior.BehaviorLedger;
import com.echo.http.behavior.ExplicitFeedbackStore;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.http.work.ResourceStore;
import com.echo.http.work.Work;
import com.echo.http.work.WorkCommentStore;
import com.echo.http.work.WorkFavoriteStore;
import com.echo.http.work.WorkStore;
import com.echo.infra.corpus.InMemoryTrainingCorpus;
import com.echo.infra.llm.MockLlmClient;
import com.echo.infra.vision.StubVisionClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 整线组合：广场看到的作品、点进去的那一条、评论三加二、收藏私有、服务端记账。
 * 不改单元内部实现，只把已验收的 Router 拼在一起。
 */
class FoundationCombinationTest {
    private static final String TITLE = "它最后一个下午";

    private final IDGenerator ids = new IDGenerator(90);
    private final InMemoryEchoStore accounts = new InMemoryEchoStore();
    private final WorkStore works = new WorkStore(null);
    private final WorkCommentStore comments = new WorkCommentStore(null);
    private final WorkFavoriteStore favorites = new WorkFavoriteStore(null);
    private final BehaviorEventStore behaviors = new BehaviorEventStore(null);
    private Router router;
    private long authorId;
    private long guestId;
    private long boundId;
    private long workId;

    @BeforeEach
    void setUp() {
        authorId = ids.nextId();
        guestId = ids.nextId();
        boundId = ids.nextId();
        profile(authorId, false, "作者");
        profile(guestId, true, "游客");
        profile(boundId, false, "路过的人");
        Work work = work(authorId, TITLE);
        works.insert(work);
        workId = work.id;

        EchoApi echo = new EchoApi(accounts, ids, new MockLlmClient(), new StubVisionClient(),
                null, new InMemoryTrainingCorpus());
        echo.setWorkStore(works);
        router = echo.routes(false);
        WorksApi worksApi = new WorksApi(works, accounts, null, new ResourceStore(null), null, ids);
        worksApi.setFavoriteStore(favorites);
        worksApi.register(router);
        WorkSocialApi social = new WorkSocialApi(works, comments, favorites, accounts, null, ids);
        social.setBehaviorLedger(new BehaviorLedger(behaviors, ids));
        social.register(router);
        new BehaviorApi(behaviors, accounts, ids, new ExplicitFeedbackStore(null),
                new BehaviorLedger(behaviors, ids)).register(router);
    }

    @Test
    void plazaDetailCommentsFavoriteAndServerFactShareOneWork() throws Exception {
        Map<String, Object> plaza = get(guestId, "/plaza");
        List<Map<String, Object>> plazaItems = items(plaza);
        assertThat(plazaItems).hasSize(1);
        assertThat(plazaItems.get(0).get("id")).isEqualTo(String.valueOf(workId));
        assertThat(plazaItems.get(0).get("title")).isEqualTo(TITLE);
        assertThat(plazaItems.get(0)).doesNotContainKey("status");
        assertThat(plazaItems.get(0)).doesNotContainKey("favoriteCount");

        Map<String, Object> detail = get(guestId, "/works/" + workId);
        Map<?, ?> work = (Map<?, ?>) detail.get("work");
        assertThat(work.get("id")).isEqualTo(String.valueOf(workId));
        assertThat(work.get("title")).isEqualTo(TITLE);
        assertThat(work.containsKey("favoriteCount")).isFalse();

        for (int i = 0; i < 5; i++) {
            post(boundId, "/works/" + workId + "/comments", Map.of("body", "根" + i, "idempotencyKey", "r" + i));
        }
        Map<String, Object> firstPage = get(boundId, "/works/" + workId + "/comments");
        String rootId = String.valueOf(((Map<?, ?>) items(firstPage).get(0).get("comment")).get("commentId"));
        post(boundId, "/comments/" + rootId + "/replies", Map.of("body", "回1", "idempotencyKey", "a1"));
        post(boundId, "/comments/" + rootId + "/replies", Map.of("body", "回2", "idempotencyKey", "a2"));
        post(boundId, "/comments/" + rootId + "/replies", Map.of("body", "回3", "idempotencyKey", "a3"));

        Map<String, Object> guestComments = get(guestId, "/works/" + workId + "/comments");
        assertThat(guestComments.get("sort")).isEqualTo("hot");
        assertThat(guestComments.get("nextCursor")).isNull();
        List<Map<String, Object>> threads = items(guestComments);
        assertThat(threads).hasSize(3);
        assertThat((List<?>) threads.get(0).get("previewReplies")).hasSize(2);
        assertThat(threads.get(0).get("remainingReplyCount")).isEqualTo(1);
        assertThat(threads.get(0).get("repliesCursor")).isNull();

        assertThatThrownBy(() -> post(guestId, "/works/" + workId + "/comments", Map.of("body", "不能写")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiException.BINDING_REQUIRED);

        put(boundId, "/works/" + workId + "/favorite");
        Map<?, ?> boundWork = (Map<?, ?>) get(boundId, "/works/" + workId).get("work");
        assertThat(boundWork.get("favorited")).isEqualTo(true);
        assertThat(boundWork.containsKey("favoriteCount")).isFalse();
        assertThat(((Map<?, ?>) get(authorId, "/works/" + workId).get("work")).containsKey("favoriteCount")).isFalse();
        assertThat(behaviors.ofAccount(boundId)).extracting(e -> e.eventName)
                .contains("work_favorite_changed");

        JsonObject stolen = new JsonObject();
        stolen.addProperty("idempotencyKey", "client-fav");
        stolen.addProperty("eventName", "work_favorite_changed");
        stolen.addProperty("sessionId", "sess");
        stolen.addProperty("surface", "work_detail");
        stolen.addProperty("targetType", "work");
        stolen.addProperty("targetId", String.valueOf(workId));
        stolen.addProperty("occurredAt", "2026-09-14T00:00:00Z");
        stolen.addProperty("schemaVersion", 1);
        stolen.addProperty("purposeCode", "public_recommendation");
        stolen.add("context", new JsonObject());
        JsonObject batch = new JsonObject();
        JsonArray events = new JsonArray();
        events.add(stolen);
        batch.add("events", events);
        Map<String, Object> ingest = postJson(guestId, "/behavior-events/batch", batch);
        assertThat(((Map<?, ?>) ((List<?>) ingest.get("results")).get(0)).get("reasonCode"))
                .isEqualTo(BehaviorDictionary.REJECT_EMITTER);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(long viewer, String path) throws Exception {
        return (Map<String, Object>) call("GET", path, viewer, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(long viewer, String path, Map<String, String> fields) throws Exception {
        JsonObject body = new JsonObject();
        fields.forEach(body::addProperty);
        return (Map<String, Object>) call("POST", path, viewer, body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postJson(long viewer, String path, JsonObject body) throws Exception {
        return (Map<String, Object>) call("POST", path, viewer, body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> put(long viewer, String path) throws Exception {
        return (Map<String, Object>) call("PUT", path, viewer, new JsonObject());
    }

    private Object call(String method, String path, long viewer, JsonObject body) throws Exception {
        String route = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        Map<String, String> query = new java.util.LinkedHashMap<>();
        if (path.contains("?")) {
            for (String part : path.substring(path.indexOf('?') + 1).split("&")) {
                String[] kv = part.split("=", 2);
                query.put(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }
        Router.Match match = router.match(method, route);
        assertThat(match).as("%s %s", method, route).isNotNull();
        return match.handle(new RequestContext(method, match.pathParams, query, body, viewer, Map.of()));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private void profile(long accountId, boolean guest, String name) {
        AccountProfile p = new AccountProfile();
        p.accountId = accountId;
        p.deviceId = "dev-" + accountId;
        p.nickname = name;
        p.guest = guest;
        accounts.putProfile(p);
    }

    private Work work(long author, String title) {
        Work w = new Work();
        w.id = ids.nextId();
        w.authorId = author;
        w.mediaType = Work.MediaType.IMAGE;
        w.mediaKey = "media";
        w.title = title;
        w.body = title;
        w.status = Work.Status.PUBLIC;
        w.visibility = "public";
        w.publishedAt = 10L;
        w.createdAt = 10;
        w.updatedAt = 10;
        return w;
    }
}
