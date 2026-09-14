package com.echo.http.work;

import com.aengine.util.id.IDGenerator;
import com.echo.http.ApiException;
import com.echo.http.RequestContext;
import com.echo.http.Router;
import com.echo.http.WorksApi;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.store.InMemoryEchoStore;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkResubmitTest {
    private final IDGenerator ids = new IDGenerator(41);
    private final InMemoryEchoStore accounts = new InMemoryEchoStore();
    private final WorkStore store = new WorkStore(null);
    private final ResourceStore resources = new ResourceStore(null);
    private Router router;
    private long accountId;

    @BeforeEach
    void setUp() {
        accountId = ids.nextId();
        AccountProfile profile = new AccountProfile();
        profile.accountId = accountId;
        profile.deviceId = "work-resubmit";
        profile.guest = false;
        accounts.putProfile(profile);
        resources.record("media-1", accountId, "media-1", "image/jpeg", 12, 1);
        WorksApi api = new WorksApi(store, accounts, null, resources, null, ids);
        router = new Router();
        api.register(router);
    }

    @Test
    void draftKeepsRejectedAndResubmitBumpsVersionOntoPending() throws Exception {
        Work rejected = rejectedWork(accountId, "media-1", "旧标题", "旧正文");
        store.insert(rejected);

        Map<String, Object> draft = call("PUT", "/works/" + rejected.id + "/draft",
                body("title", "改过的标题", "body", "改过的正文"), null);
        Work afterDraft = store.byId(rejected.id);
        assertThat(afterDraft.status).isEqualTo(Work.Status.REJECTED);
        assertThat(afterDraft.contentVersion).isEqualTo(2);
        assertThat(afterDraft.submittedContentVersion).isEqualTo(1);
        assertThat(afterDraft.title).isEqualTo("改过的标题");
        assertThat(((Map<?, ?>) draft.get("work")).get("nextAction")).isEqualTo("resubmit");

        Map<String, Object> first = call("POST", "/works/" + rejected.id + "/resubmit",
                body("contentVersion", 2, "idempotencyKey", "resubmit-1"), null);
        assertThat(first).containsEntry("status", Work.Status.PENDING)
                .containsEntry("contentVersion", 2)
                .containsEntry("workId", String.valueOf(rejected.id));
        assertThat(first.get("contentHash")).isEqualTo(WorkContent.hash(store.byId(rejected.id)));
        assertThat(first.get("moderationId")).isNotNull();
        assertThat(store.byId(rejected.id).status).isEqualTo(Work.Status.PENDING);
        assertThat(store.occupyingWork(accountId).id).isEqualTo(rejected.id);

        Map<String, Object> replay = call("POST", "/works/" + rejected.id + "/resubmit",
                body("contentVersion", 2, "idempotencyKey", "resubmit-1"), null);
        assertThat(replay).isEqualTo(first);
    }

    @Test
    void takendownCannotUseThisDoorAndStaleVersionIsRejected() throws Exception {
        Work down = rejectedWork(accountId, "media-1", "a", "b");
        down.status = Work.Status.TAKENDOWN;
        store.insert(down);
        assertThatThrownBy(() -> call("PUT", "/works/" + down.id + "/draft",
                body("title", "x"), null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.detail()).isEqualTo("work_takendown"));

        Work rejected = rejectedWork(accountId, "media-1", "a", "b");
        rejected.id = ids.nextId();
        store.insert(rejected);
        assertThatThrownBy(() -> call("POST", "/works/" + rejected.id + "/resubmit",
                body("contentVersion", 99, "idempotencyKey", "stale"), null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.detail()).isEqualTo("work_version_conflict"));
    }

    @Test
    void occupiedSlotBlocksAnotherResubmit() throws Exception {
        Work pending = rejectedWork(accountId, "media-1", "占着", "名额");
        pending.status = Work.Status.PENDING;
        store.insert(pending);
        Work rejected = rejectedWork(accountId, "media-1", "被挡", "住了");
        rejected.id = ids.nextId();
        store.insert(rejected);
        assertThatThrownBy(() -> call("POST", "/works/" + rejected.id + "/resubmit",
                body("contentVersion", 1, "idempotencyKey", "blocked"), null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.detail()).isEqualTo("submission_slot_occupied"));
        assertThat(store.byId(rejected.id).status).isEqualTo(Work.Status.REJECTED);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String method, String path, JsonObject body, String headerKey)
            throws Exception {
        Router.Match match = router.match(method, path);
        assertThat(match).isNotNull();
        Map<String, String> headers = headerKey == null ? Map.of() : Map.of("idempotency-key", headerKey);
        Object result = match.handle(new RequestContext(method, match.pathParams, Map.of(), body, accountId, headers));
        return (Map<String, Object>) result;
    }

    private Work rejectedWork(long authorId, String mediaKey, String title, String body) {
        Work w = new Work();
        w.id = ids.nextId();
        w.authorId = authorId;
        w.mediaType = Work.MediaType.IMAGE;
        w.mediaKey = mediaKey;
        w.title = title;
        w.body = body;
        w.status = Work.Status.REJECTED;
        w.originType = Work.OriginType.USER;
        w.createdAt = 10;
        w.updatedAt = 10;
        w.contentVersion = 1;
        w.submittedContentVersion = 1;
        w.contentHash = WorkContent.hash(w);
        w.submittedContentHash = w.contentHash;
        return w;
    }

    private static JsonObject body(Object... pairs) {
        JsonObject json = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value instanceof Boolean bool) json.addProperty(String.valueOf(pairs[i]), bool);
            else if (value instanceof Number number) json.addProperty(String.valueOf(pairs[i]), number);
            else json.addProperty(String.valueOf(pairs[i]), String.valueOf(value));
        }
        return json;
    }
}
