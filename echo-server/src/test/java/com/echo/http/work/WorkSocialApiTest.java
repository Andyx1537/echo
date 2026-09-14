package com.echo.http.work;

import com.aengine.util.id.IDGenerator;
import com.echo.http.ApiException;
import com.echo.http.RequestContext;
import com.echo.http.Router;
import com.echo.http.WorkSocialApi;
import com.echo.http.WorksApi;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.store.InMemoryEchoStore;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkSocialApiTest {
    private final IDGenerator ids = new IDGenerator(77);
    private final InMemoryEchoStore accounts = new InMemoryEchoStore();
    private final WorkStore works = new WorkStore(null);
    private final WorkCommentStore comments = new WorkCommentStore(null);
    private final WorkFavoriteStore favorites = new WorkFavoriteStore(null);
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
        put(authorId, false, "作者");
        put(guestId, true, "游客");
        put(boundId, false, "路过的人");
        Work work = work(authorId, Work.Status.PUBLIC, "公开的");
        works.insert(work);
        workId = work.id;
        WorksApi worksApi = new WorksApi(works, accounts, null, new ResourceStore(null), null, ids);
        worksApi.setFavoriteStore(favorites);
        WorkSocialApi social = new WorkSocialApi(works, comments, favorites, accounts, null, ids);
        router = new Router();
        worksApi.register(router);
        social.register(router);
    }

    @Test
    void guestSeesThreePlusTwoAndCannotWriteOrPage() throws Exception {
        for (int i = 0; i < 5; i++) {
            post(boundId, "/works/" + workId + "/comments", Map.of("body", "根" + i, "idempotencyKey", "r" + i));
        }
        WorkComment root = comments.visibleRoots(workId, "latest").get(0);
        post(boundId, "/comments/" + root.id + "/replies", Map.of("body", "回1", "idempotencyKey", "a1"));
        post(boundId, "/comments/" + root.id + "/replies", Map.of("body", "回2", "idempotencyKey", "a2"));
        post(boundId, "/comments/" + root.id + "/replies", Map.of("body", "回3", "idempotencyKey", "a3"));

        Map<String, Object> page = get(guestId, "/works/" + workId + "/comments");
        assertThat(page.get("sort")).isEqualTo("hot");
        assertThat(page.get("nextCursor")).isNull();
        List<Map<String, Object>> items = items(page);
        assertThat(items).hasSize(3);
        Map<String, Object> first = items.get(0);
        assertThat((List<?>) first.get("previewReplies")).hasSize(2);
        assertThat(first.get("remainingReplyCount")).isEqualTo(1);
        assertThat(first.get("repliesCursor")).isNull();
        assertThat(((Map<?, ?>) first.get("comment")).containsKey("favoriteCount")).isFalse();

        assertThatThrownBy(() -> post(guestId, "/works/" + workId + "/comments", Map.of("body", "不能写")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiException.BINDING_REQUIRED);
        assertThatThrownBy(() -> get(guestId, "/works/" + workId + "/comments?cursor=abc"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiException.BINDING_REQUIRED);
    }

    @Test
    void replyStaysTwoLevelsAndRootDeleteHidesTheTree() throws Exception {
        Map<String, Object> rootRes = post(boundId, "/works/" + workId + "/comments",
                Map.of("body", "想它", "idempotencyKey", "c1"));
        String rootId = String.valueOf(((Map<?, ?>) rootRes.get("comment")).get("commentId"));
        Map<String, Object> r1 = post(boundId, "/comments/" + rootId + "/replies",
                Map.of("body", "我也是", "idempotencyKey", "c2"));
        String replyId = String.valueOf(((Map<?, ?>) r1.get("comment")).get("commentId"));
        Map<String, Object> r2 = post(boundId, "/comments/" + replyId + "/replies",
                Map.of("body", "回你一句", "idempotencyKey", "c3"));
        Map<?, ?> nested = (Map<?, ?>) r2.get("comment");
        assertThat(nested.get("rootCommentId")).isEqualTo(rootId);
        assertThat(nested.get("replyToCommentId")).isEqualTo(replyId);
        assertThat(nested.get("replyToLabel")).isEqualTo("路过的人");

        Map<String, Object> del = delete(boundId, "/comments/" + rootId);
        assertThat(del.get("displayState")).isEqualTo("hidden");
        assertThat(((Number) del.get("cascadedReplyCount")).intValue()).isEqualTo(2);
        assertThat(del.get("visibleCommentCount")).isEqualTo(0);
        Map<String, Object> page = get(boundId, "/works/" + workId + "/comments");
        assertThat(items(page)).isEmpty();
    }

    @Test
    void favoriteIsPrivateAndNeverCountedOnWork() throws Exception {
        put(boundId, "/works/" + workId + "/favorite");
        Map<String, Object> detail = get(boundId, "/works/" + workId);
        Map<?, ?> work = (Map<?, ?>) detail.get("work");
        assertThat(work.get("favorited")).isEqualTo(true);
        assertThat(work.containsKey("favoriteCount")).isFalse();
        Map<String, Object> authorView = get(authorId, "/works/" + workId);
        assertThat(((Map<?, ?>) authorView.get("work")).containsKey("favoriteCount")).isFalse();
        Map<String, Object> mine = get(boundId, "/me/favorites");
        assertThat(items(mine)).extracting(item -> item.get("id")).contains(String.valueOf(workId));
        assertThat(items(mine).get(0)).doesNotContainKey("favoriteCount");
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
    private Map<String, Object> put(long viewer, String path) throws Exception {
        return (Map<String, Object>) call("PUT", path, viewer, new JsonObject());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> delete(long viewer, String path) throws Exception {
        return (Map<String, Object>) call("DELETE", path, viewer, null);
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

    private void put(long accountId, boolean guest, String name) {
        AccountProfile p = new AccountProfile();
        p.accountId = accountId;
        p.deviceId = "dev-" + accountId;
        p.nickname = name;
        p.guest = guest;
        accounts.putProfile(p);
    }

    private Work work(long author, String status, String title) {
        Work w = new Work();
        w.id = ids.nextId();
        w.authorId = author;
        w.mediaType = Work.MediaType.IMAGE;
        w.mediaKey = "media";
        w.title = title;
        w.body = title;
        w.status = status;
        w.visibility = "public";
        w.publishedAt = 10L;
        w.createdAt = 10;
        w.updatedAt = 10;
        return w;
    }
}
