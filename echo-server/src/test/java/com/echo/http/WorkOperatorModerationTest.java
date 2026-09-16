package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.http.store.InMemoryModerationStore;
import com.echo.http.work.ResourceStore;
import com.echo.http.work.Work;
import com.echo.http.work.WorkModerationStore;
import com.echo.http.work.WorkModerationTicket;
import com.echo.http.work.WorkStore;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkOperatorModerationTest {
    private static final long REVIEWER = 1002L;

    private final IDGenerator ids = new IDGenerator(47);
    private final InMemoryEchoStore accounts = new InMemoryEchoStore();
    private final WorkStore works = new WorkStore(null);
    private final ResourceStore resources = new ResourceStore(null);
    private final WorkModerationStore tickets = new WorkModerationStore(null, ids);
    private Router router;
    private long authorId;

    @BeforeEach
    void setUp() {
        authorId = ids.nextId();
        AccountProfile author = new AccountProfile();
        author.accountId = authorId;
        author.deviceId = "work-operator";
        author.guest = false;
        accounts.putProfile(author);
        AccountProfile reviewer = new AccountProfile();
        reviewer.accountId = REVIEWER;
        reviewer.deviceId = "reviewer";
        reviewer.guest = false;
        accounts.putProfile(reviewer);
        resources.record("media-1", authorId, "media-1", "image/jpeg", 12, 1);

        WorksApi worksApi = new WorksApi(works, accounts, null, resources, null, ids);
        worksApi.setWorkModerationStore(tickets);
        ModerationApi moderationApi = new ModerationApi(new InMemoryModerationStore(ids),
                AdminRoles.parse(REVIEWER + ":reviewer"), ids);
        moderationApi.setWorkModeration(works, tickets);
        router = new Router();
        worksApi.register(router);
        moderationApi.register(router);
    }

    @Test
    void pendingUploadNeedsApproveBeforePlaza() throws Exception {
        Map<String, Object> published = call(authorId, "POST", "/works",
                body("mediaType", "image", "mediaKey", "media-1", "title", "自制", "body", "上传"));
        assertThat(published).containsEntry("status", Work.Status.PENDING);
        long workId = Long.parseLong(String.valueOf(published.get("workId")));
        assertThat(works.publicWorks(10)).isEmpty();
        assertThat(works.occupyingWork(authorId).id).isEqualTo(workId);

        Map<String, Object> queue = call(REVIEWER, "GET", "/admin/moderation/queue?targetType=work", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) queue.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("targetType", "work")
                .containsEntry("workId", String.valueOf(workId))
                .containsEntry("state", WorkModerationTicket.State.QUEUED);
        long moderationId = Long.parseLong(String.valueOf(items.get(0).get("moderationId")));
        int stateVersion = ((Number) items.get(0).get("stateVersion")).intValue();

        Map<String, Object> approved = call(REVIEWER, "POST", "/admin/moderation/" + moderationId + "/handle",
                body("action", "approve", "expectedStateVersion", stateVersion));
        assertThat(approved).containsEntry("state", WorkModerationTicket.State.APPROVED)
                .containsEntry("workStatus", Work.Status.PUBLIC);
        assertThat(approved.get("reviewedAt")).isNotNull();
        Work after = works.byId(workId);
        assertThat(after.status).isEqualTo(Work.Status.PUBLIC);
        assertThat(after.reviewedAt).isEqualTo(approved.get("reviewedAt"));
        assertThat(works.occupyingWork(authorId)).isNull();
        assertThat(works.publicWorks(10)).extracting(w -> w.id).containsExactly(workId);
        assertThat(tickets.memoryAudits()).hasSize(1);

        Long firstReviewedAt = after.reviewedAt;
        assertThatThrownBy(() -> call(REVIEWER, "POST", "/admin/moderation/" + moderationId + "/handle",
                body("action", "approve", "expectedStateVersion", stateVersion)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ModerationStateMachine.ERR_STATE_CONFLICT);
                    assertThat(ex.detail()).isEqualTo("moderation_state_conflict");
                    assertThat(ex.data()).containsEntry("retryable", false)
                            .containsEntry("currentState", WorkModerationTicket.State.APPROVED);
                });
        assertThat(works.byId(workId).reviewedAt).isEqualTo(firstReviewedAt);
    }

    @Test
    void rejectFreesSlotAndSecondApproveConflicts() throws Exception {
        Map<String, Object> published = call(authorId, "POST", "/works",
                body("mediaType", "image", "mediaKey", "media-1", "title", "待驳", "body", "正文"));
        long workId = Long.parseLong(String.valueOf(published.get("workId")));
        WorkModerationTicket ticket = tickets.activeByWork(workId);

        assertThatThrownBy(() -> call(REVIEWER, "POST", "/admin/moderation/" + ticket.id + "/handle",
                body("action", "reject", "expectedStateVersion", ticket.stateVersion)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.detail()).isEqualTo("reason_code_required"));

        Map<String, Object> rejected = call(REVIEWER, "POST", "/admin/moderation/" + ticket.id + "/handle",
                body("action", "reject", "expectedStateVersion", ticket.stateVersion,
                        "reasonCode", "policy"));
        assertThat(rejected).containsEntry("state", WorkModerationTicket.State.REJECTED)
                .containsEntry("workStatus", Work.Status.REJECTED);
        assertThat(rejected.get("reviewedAt")).isNull();
        assertThat(works.byId(workId).status).isEqualTo(Work.Status.REJECTED);
        assertThat(works.occupyingWork(authorId)).isNull();
        assertThat(works.publicWorks(10)).isEmpty();
    }

    @Test
    void resubmitOpensANewTicketAfterReject() throws Exception {
        Map<String, Object> published = call(authorId, "POST", "/works",
                body("mediaType", "image", "mediaKey", "media-1", "title", "先驳", "body", "再提"));
        long workId = Long.parseLong(String.valueOf(published.get("workId")));
        WorkModerationTicket first = tickets.activeByWork(workId);
        call(REVIEWER, "POST", "/admin/moderation/" + first.id + "/handle",
                body("action", "reject", "expectedStateVersion", first.stateVersion, "reasonCode", "policy"));

        call(authorId, "PUT", "/works/" + workId + "/draft", body("title", "改过", "body", "再提"));
        Map<String, Object> resubmitted = call(authorId, "POST", "/works/" + workId + "/resubmit",
                body("contentVersion", 2, "idempotencyKey", "again"));
        assertThat(resubmitted).containsEntry("status", Work.Status.PENDING);
        WorkModerationTicket second = tickets.activeByWork(workId);
        assertThat(second.id).isNotEqualTo(first.id);
        assertThat(second.contentVersion).isEqualTo(2);

        Map<String, Object> approved = call(REVIEWER, "POST", "/admin/moderation/" + second.id + "/handle",
                body("action", "approve", "expectedStateVersion", second.stateVersion));
        assertThat(approved).containsEntry("workStatus", Work.Status.PUBLIC);
        assertThat(works.publicWorks(10)).extracting(w -> w.id).containsExactly(workId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(long accountId, String method, String path, JsonObject body)
            throws Exception {
        String route = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        Map<String, String> query = Map.of();
        if (path.contains("?")) {
            String[] pair = path.substring(path.indexOf('?') + 1).split("=");
            query = Map.of(pair[0], pair[1]);
        }
        Router.Match match = router.match(method, route);
        assertThat(match).isNotNull();
        Object result = match.handle(new RequestContext(method, match.pathParams, query, body, accountId, Map.of()));
        return (Map<String, Object>) result;
    }

    private static JsonObject body(Object... pairs) {
        JsonObject json = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value instanceof Boolean bool) {
                json.addProperty(String.valueOf(pairs[i]), bool);
            } else if (value instanceof Number number) {
                json.addProperty(String.valueOf(pairs[i]), number);
            } else {
                json.addProperty(String.valueOf(pairs[i]), String.valueOf(value));
            }
        }
        return json;
    }
}
