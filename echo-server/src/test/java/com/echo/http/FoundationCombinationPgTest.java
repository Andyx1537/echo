package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.behavior.BehaviorDictionary;
import com.echo.http.behavior.BehaviorEventStore;
import com.echo.http.behavior.BehaviorLedger;
import com.echo.http.behavior.ExplicitFeedbackStore;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.http.work.AnonPlazaBatchStore;
import com.echo.http.work.ResourceStore;
import com.echo.http.work.Work;
import com.echo.http.work.WorkCommentStore;
import com.echo.http.work.WorkFavoriteStore;
import com.echo.http.work.WorkStore;
import com.echo.infra.corpus.InMemoryTrainingCorpus;
import com.echo.infra.llm.MockLlmClient;
import com.echo.infra.persistence.PgDb;
import com.echo.infra.vision.StubVisionClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 整线组合走真实 PG：广场、详情、评论三加二、收藏私有。
 * 门控 {@code ECHO_TEST_PG_URL}；常规 {@code mvn test} 无库时跳过。
 */
class FoundationCombinationPgTest {
    private static final String TITLE = "它最后一个下午";
    private static PgDb db;

    private final IDGenerator ids = new IDGenerator(91);
    private final InMemoryEchoStore accounts = new InMemoryEchoStore();
    private WorkStore works;
    private WorkCommentStore comments;
    private WorkFavoriteStore favorites;
    private final BehaviorEventStore behaviors = new BehaviorEventStore(null);
    private Router router;
    private long authorId;
    private long guestId;
    private long boundId;
    private long workId;

    @BeforeAll
    static void connect() {
        String url = System.getenv("ECHO_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "设置 ECHO_TEST_PG_URL 后运行真实组合 PG 测试");
        Properties p = new Properties();
        p.setProperty("db.name", "echo");
        p.setProperty("jdbcUrl", url);
        p.setProperty("driverClassName", "org.postgresql.Driver");
        p.setProperty("username", System.getenv().getOrDefault("ECHO_TEST_PG_USER", "echo"));
        String password = System.getenv("ECHO_TEST_PG_PASSWORD");
        if (password != null) {
            p.setProperty("password", password);
        }
        p.setProperty("maximumPoolSize", "6");
        db = new PgDb(p);
        try {
            db.query("SELECT 1 FROM \"t_work_comment\" LIMIT 1", null);
            db.query("SELECT 1 FROM \"t_work_favorite\" LIMIT 1", null);
            db.query("SELECT 1 FROM \"t_anon_plaza_batch\" LIMIT 1", null);
        } catch (SQLException e) {
            throw new AssertionError("测试库缺少评论/收藏/匿名批次表，需要 schema 2026091405+", e);
        }
    }

    @AfterAll
    static void close() {
        if (db != null) {
            db.shutdown();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(db != null);
        for (String table : new String[]{"t_work_favorite", "t_work_comment", "t_anon_plaza_batch", "t_work", "t_account"}) {
            db.update("DELETE FROM \"" + table + "\"");
        }
        authorId = ids.nextId();
        guestId = ids.nextId();
        boundId = ids.nextId();
        persistAccount(authorId);
        persistAccount(guestId);
        persistAccount(boundId);
        profile(authorId, false, "作者");
        profile(guestId, true, "游客");
        profile(boundId, false, "路过的人");

        works = new WorkStore(db);
        comments = new WorkCommentStore(db);
        favorites = new WorkFavoriteStore(db);
        Work work = work(authorId, TITLE);
        assertThat(works.insert(work)).isTrue();
        workId = work.id;

        // 换一套仓储实例，证明不是内存里的同一份对象。
        works = new WorkStore(db);
        comments = new WorkCommentStore(db);
        favorites = new WorkFavoriteStore(db);
        wireRouter();
    }

    @Test
    void plazaDetailCommentsFavoriteShareOneWorkAcrossStoreRestart() throws Exception {
        Map<String, Object> plaza = get(guestId, "/plaza");
        List<Map<String, Object>> plazaItems = items(plaza);
        assertThat(plazaItems).hasSize(1);
        assertThat(plazaItems.get(0).get("id")).isEqualTo(String.valueOf(workId));
        assertThat(plazaItems.get(0).get("title")).isEqualTo(TITLE);
        assertThat(plazaItems.get(0)).doesNotContainKey("favoriteCount");

        Map<?, ?> work = (Map<?, ?>) get(guestId, "/works/" + workId).get("work");
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

        comments = new WorkCommentStore(db);
        favorites = new WorkFavoriteStore(db);
        works = new WorkStore(db);
        wireRouter();

        Map<String, Object> guestComments = get(guestId, "/works/" + workId + "/comments");
        assertThat(guestComments.get("sort")).isEqualTo("hot");
        assertThat(guestComments.get("nextCursor")).isNull();
        List<Map<String, Object>> threads = items(guestComments);
        assertThat(threads).hasSize(3);
        assertThat((List<?>) threads.get(0).get("previewReplies")).hasSize(2);
        assertThat(threads.get(0).get("remainingReplyCount")).isEqualTo(1);

        assertThatThrownBy(() -> post(guestId, "/works/" + workId + "/comments", Map.of("body", "不能写")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiException.BINDING_REQUIRED);

        put(boundId, "/works/" + workId + "/favorite");
        favorites = new WorkFavoriteStore(db);
        works = new WorkStore(db);
        wireRouter();
        Map<?, ?> boundWork = (Map<?, ?>) get(boundId, "/works/" + workId).get("work");
        assertThat(boundWork.get("favorited")).isEqualTo(true);
        assertThat(boundWork.containsKey("favoriteCount")).isFalse();
        assertThat(new WorkFavoriteStore(db).favorited(boundId, workId)).isTrue();

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

    private void wireRouter() {
        EchoApi echo = new EchoApi(accounts, ids, new MockLlmClient(), new StubVisionClient(),
                null, new InMemoryTrainingCorpus());
        echo.setWorkStore(works);
        echo.setAnonPlazaBatchStore(new AnonPlazaBatchStore(db));
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

    private void persistAccount(long accountId) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO \"t_account\" (\"id\",\"openId\",\"status\",\"createTime\") VALUES (?,?,0,1)")) {
            ps.setLong(1, accountId);
            ps.setString(2, "open-" + accountId);
            ps.executeUpdate();
        }
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
        Router.Match match = router.match(method, path);
        assertThat(match).as("%s %s", method, path).isNotNull();
        return match.handle(new RequestContext(method, match.pathParams, Map.of(), body, viewer, Map.of()));
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
        w.originType = Work.OriginType.USER;
        w.publishedAt = 10L;
        w.createdAt = 10;
        w.updatedAt = 10;
        return w;
    }
}
