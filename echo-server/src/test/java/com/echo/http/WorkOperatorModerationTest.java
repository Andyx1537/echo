package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.http.store.InMemoryModerationStore;
import com.echo.http.model.ModerationModels.CardStatus;
import com.echo.http.model.ModerationModels.MemoryCard;
import com.echo.http.work.ResourceStore;
import com.echo.http.work.Work;
import com.echo.http.work.WorkContent;
import com.echo.http.work.WorkModerationStore;
import com.echo.http.work.WorkModerationTicket;
import com.echo.http.work.WorkReviewDecision;
import com.echo.http.work.WorkReviewEvidence;
import com.echo.http.work.WorkReviewEvidenceStore;
import com.echo.http.work.WorkStore;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkOperatorModerationTest {
    private static final long SUPERVISOR = 1001L;
    private static final long REVIEWER = 1002L;

    private final IDGenerator ids = new IDGenerator(47);
    private final InMemoryEchoStore accounts = new InMemoryEchoStore();
    private final WorkStore works = new WorkStore(null);
    private final ResourceStore resources = new ResourceStore(null);
    private final WorkModerationStore tickets = new WorkModerationStore(null, ids);
    private final WorkReviewEvidenceStore evidence = new WorkReviewEvidenceStore(null);
    private final InMemoryModerationStore cards = new InMemoryModerationStore(ids);
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
        AccountProfile supervisor = new AccountProfile();
        supervisor.accountId = SUPERVISOR;
        supervisor.deviceId = "supervisor";
        supervisor.guest = false;
        accounts.putProfile(supervisor);
        resources.record("media-1", authorId, "media-1", "image/jpeg", 12, 1);

        WorksApi worksApi = new WorksApi(works, accounts, null, resources, null, ids);
        worksApi.setWorkModerationStore(tickets);
        worksApi.setCardStore(cards);
        worksApi.setReviewEvidenceStore(evidence);
        ModerationApi moderationApi = new ModerationApi(new InMemoryModerationStore(ids),
                AdminRoles.parse(SUPERVISOR + ":supervisor," + REVIEWER + ":reviewer"), ids);
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

    @Test
    void takedownLeavesPlazaAndRestoreKeepsFirstReviewedAt() throws Exception {
        Map<String, Object> published = call(authorId, "POST", "/works",
                body("mediaType", "image", "mediaKey", "media-1", "title", "过审", "body", "再下"));
        long workId = Long.parseLong(String.valueOf(published.get("workId")));
        WorkModerationTicket queued = tickets.activeByWork(workId);
        Map<String, Object> approved = call(REVIEWER, "POST", "/admin/moderation/" + queued.id + "/handle",
                body("action", "approve", "expectedStateVersion", queued.stateVersion));
        Long firstReviewedAt = (Long) approved.get("reviewedAt");
        int version = ((Number) approved.get("stateVersion")).intValue();

        assertThatThrownBy(() -> call(REVIEWER, "POST", "/admin/moderation/" + queued.id + "/handle",
                body("action", "takedown", "expectedStateVersion", version)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.detail()).isEqualTo("reason_code_required"));

        Map<String, Object> down = call(REVIEWER, "POST", "/admin/moderation/" + queued.id + "/handle",
                body("action", "takedown", "expectedStateVersion", version, "reasonCode", "policy"));
        assertThat(down).containsEntry("state", WorkModerationTicket.State.TAKENDOWN)
                .containsEntry("workStatus", Work.Status.TAKENDOWN);
        assertThat(works.publicWorks(10)).isEmpty();
        assertThat(works.byId(workId).reviewedAt).isEqualTo(firstReviewedAt);

        int downVersion = ((Number) down.get("stateVersion")).intValue();
        Map<String, Object> restored = call(REVIEWER, "POST", "/admin/moderation/" + queued.id + "/handle",
                body("action", "restore", "expectedStateVersion", downVersion));
        assertThat(restored).containsEntry("state", WorkModerationTicket.State.APPROVED)
                .containsEntry("workStatus", Work.Status.PUBLIC)
                .containsEntry("reviewedAt", firstReviewedAt);
        assertThat(works.publicWorks(10)).extracting(w -> w.id).containsExactly(workId);
        assertThat(works.byId(workId).reviewedAt).isEqualTo(firstReviewedAt);
    }

    @Test
    void publicAndTakendownTabsFollowHandle() throws Exception {
        Map<String, Object> published = call(authorId, "POST", "/works",
                body("mediaType", "image", "mediaKey", "media-1", "title", "栏", "body", "下架栏"));
        long workId = Long.parseLong(String.valueOf(published.get("workId")));
        WorkModerationTicket queued = tickets.activeByWork(workId);
        assertThat(items(call(REVIEWER, "GET", "/admin/moderation/queue?targetType=work&tab=public", null)))
                .isEmpty();

        Map<String, Object> approved = call(REVIEWER, "POST", "/admin/moderation/" + queued.id + "/handle",
                body("action", "approve", "expectedStateVersion", queued.stateVersion));
        List<Map<String, Object>> publicItems =
                items(call(REVIEWER, "GET", "/admin/moderation/queue?targetType=work&tab=public", null));
        assertThat(publicItems).hasSize(1);
        assertThat(publicItems.get(0)).containsEntry("workId", String.valueOf(workId))
                .containsEntry("state", WorkModerationTicket.State.APPROVED);

        int version = ((Number) approved.get("stateVersion")).intValue();
        call(REVIEWER, "POST", "/admin/moderation/" + queued.id + "/handle",
                body("action", "takedown", "expectedStateVersion", version, "reasonCode", "policy"));
        assertThat(items(call(REVIEWER, "GET", "/admin/moderation/queue?targetType=work&tab=public", null)))
                .isEmpty();
        List<Map<String, Object>> downItems =
                items(call(REVIEWER, "GET", "/admin/moderation/queue?targetType=work&tab=takendown", null));
        assertThat(downItems).hasSize(1);
        assertThat(downItems.get(0)).containsEntry("state", WorkModerationTicket.State.TAKENDOWN);
    }

    @Test
    void reusedPublicGetsATicketAndCanBeTakenDown() throws Exception {
        long cardId = ids.nextId();
        MemoryCard card = new MemoryCard();
        card.id = cardId;
        card.ownerId = authorId;
        card.coverKey = "media-1";
        card.title = "原标题";
        card.body = "原文";
        card.status = CardStatus.PUBLIC;
        card.originType = "user";
        cards.putCard(card);
        Work hash = new Work();
        hash.mediaType = Work.MediaType.IMAGE;
        hash.mediaKey = "media-1";
        hash.title = "原标题";
        hash.body = "原文";
        long evidenceId = ids.nextId();
        evidence.put(WorkReviewEvidence.passed(evidenceId, cardId, authorId,
                WorkContent.reviewHash(hash), System.currentTimeMillis()));

        Map<String, Object> published = call(authorId, "POST", "/works",
                body("mediaType", "image", "mediaKey", "media-1", "title", "原标题", "body", "原文",
                        "sourceCardId", String.valueOf(cardId),
                        "reviewEvidenceId", String.valueOf(evidenceId)));
        assertThat(published).containsEntry("status", Work.Status.PUBLIC)
                .containsEntry("reviewMode", WorkReviewDecision.MODE_REUSED);
        long workId = Long.parseLong(String.valueOf(published.get("workId")));
        Work created = works.byId(workId);
        assertThat(created.lastModerationId).isNotNull();
        WorkModerationTicket ticket = tickets.byId(created.lastModerationId);
        assertThat(ticket.state).isEqualTo(WorkModerationTicket.State.APPROVED);
        assertThat(works.publicWorks(10)).extracting(w -> w.id).containsExactly(workId);

        Map<String, Object> down = call(REVIEWER, "POST", "/admin/moderation/" + ticket.id + "/handle",
                body("action", "takedown", "expectedStateVersion", ticket.stateVersion,
                        "reasonCode", "policy"));
        assertThat(down).containsEntry("workStatus", Work.Status.TAKENDOWN);
        assertThat(works.publicWorks(10)).isEmpty();
    }

    @Test
    void rejectThenAppealUpholdOnce() throws Exception {
        Map<String, Object> published = call(authorId, "POST", "/works",
                body("mediaType", "image", "mediaKey", "media-1", "title", "先驳", "body", "再申"));
        long workId = Long.parseLong(String.valueOf(published.get("workId")));
        WorkModerationTicket ticket = tickets.activeByWork(workId);
        call(REVIEWER, "POST", "/admin/moderation/" + ticket.id + "/handle",
                body("action", "reject", "expectedStateVersion", ticket.stateVersion, "reasonCode", "policy"));

        Map<String, Object> mine = call(authorId, "GET", "/works/" + workId + "/moderation", null);
        assertThat(mine).containsEntry("status", Work.Status.REJECTED)
                .containsEntry("appealable", true)
                .containsEntry("appealUsed", false);
        assertThat(works.occupyingWork(authorId)).isNull();

        Map<String, Object> appealed = call(authorId, "POST", "/works/" + workId + "/appeal",
                body("text", "我想再请你们看一眼"));
        assertThat(appealed).containsEntry("state", Work.Status.APPEALING);
        assertThat(works.byId(workId).status).isEqualTo(Work.Status.APPEALING);
        assertThat(works.occupyingWork(authorId)).isNull();
        assertThat(tickets.appealUsed(workId)).isTrue();

        Map<String, Object> queue = call(REVIEWER, "GET",
                "/admin/moderation/queue?targetType=work&tab=appealing", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) queue.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("state", WorkModerationTicket.State.APPEALING);

        assertThatThrownBy(() -> call(REVIEWER, "POST", "/admin/appeals/" + ticket.id + "/handle",
                body("action", "uphold", "expectedStateVersion", ticket.stateVersion + 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ApiException.RULE_FORBIDDEN));

        WorkModerationTicket appealing = tickets.byId(ticket.id);
        Map<String, Object> upheld = call(SUPERVISOR, "POST", "/admin/appeals/" + ticket.id + "/handle",
                body("action", "uphold", "expectedStateVersion", appealing.stateVersion));
        assertThat(upheld).containsEntry("workStatus", Work.Status.REJECTED)
                .containsEntry("state", WorkModerationTicket.State.REJECTED);
        assertThat(works.byId(workId).status).isEqualTo(Work.Status.REJECTED);
        assertThat(tickets.byId(ticket.id).appealAt).isNotNull();

        assertThatThrownBy(() -> call(authorId, "POST", "/works/" + workId + "/appeal",
                body("text", "再申一次")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ModerationStateMachine.ERR_APPEAL_USED);
                    assertThat(ex.detail()).isEqualTo("appeal_already_used");
                });
        Map<String, Object> after = call(authorId, "GET", "/works/" + workId + "/moderation", null);
        assertThat(after).containsEntry("appealable", false).containsEntry("appealUsed", true);
    }

    @Test
    void takedownAppealOverturnGoesPendingNotPublic() throws Exception {
        Map<String, Object> published = call(authorId, "POST", "/works",
                body("mediaType", "image", "mediaKey", "media-1", "title", "过审", "body", "再申"));
        long workId = Long.parseLong(String.valueOf(published.get("workId")));
        WorkModerationTicket queued = tickets.activeByWork(workId);
        Map<String, Object> approved = call(REVIEWER, "POST", "/admin/moderation/" + queued.id + "/handle",
                body("action", "approve", "expectedStateVersion", queued.stateVersion));
        Long firstReviewedAt = (Long) approved.get("reviewedAt");
        int version = ((Number) approved.get("stateVersion")).intValue();
        call(REVIEWER, "POST", "/admin/moderation/" + queued.id + "/handle",
                body("action", "takedown", "expectedStateVersion", version, "reasonCode", "policy"));

        call(authorId, "POST", "/works/" + workId + "/appeal", body("text", "请再审一次"));
        WorkModerationTicket appealing = tickets.byId(queued.id);
        Map<String, Object> overturned = call(SUPERVISOR, "POST", "/admin/appeals/" + queued.id + "/handle",
                body("action", "overturn", "expectedStateVersion", appealing.stateVersion));
        assertThat(overturned).containsEntry("workStatus", Work.Status.PENDING)
                .containsEntry("state", WorkModerationTicket.State.QUEUED);
        Work after = works.byId(workId);
        assertThat(after.status).isEqualTo(Work.Status.PENDING);
        assertThat(after.reviewedAt).isEqualTo(firstReviewedAt);
        assertThat(works.publicWorks(10)).isEmpty();
        assertThat(works.occupyingWork(authorId).id).isEqualTo(workId);
        assertThat(tickets.byId(queued.id).appealAt).isNotNull();

        WorkModerationTicket again = tickets.activeByWork(workId);
        assertThat(again.id).isEqualTo(queued.id);
        Map<String, Object> passed = call(REVIEWER, "POST", "/admin/moderation/" + again.id + "/handle",
                body("action", "approve", "expectedStateVersion", again.stateVersion));
        assertThat(passed).containsEntry("workStatus", Work.Status.PUBLIC)
                .containsEntry("reviewedAt", firstReviewedAt);
    }

    @Test
    void overturnFailsClosedWhenAnotherWorkOccupiesSlot() throws Exception {
        Map<String, Object> first = call(authorId, "POST", "/works",
                body("mediaType", "image", "mediaKey", "media-1", "title", "第一条", "body", "驳回后申"));
        long firstId = Long.parseLong(String.valueOf(first.get("workId")));
        WorkModerationTicket ticket = tickets.activeByWork(firstId);
        call(REVIEWER, "POST", "/admin/moderation/" + ticket.id + "/handle",
                body("action", "reject", "expectedStateVersion", ticket.stateVersion, "reasonCode", "policy"));
        call(authorId, "POST", "/works/" + firstId + "/appeal", body("text", "请再看"));

        resources.record("media-2", authorId, "media-2", "image/jpeg", 12, 1);
        Map<String, Object> second = call(authorId, "POST", "/works",
                body("mediaType", "image", "mediaKey", "media-2", "title", "第二条", "body", "占着名额"));
        long secondId = Long.parseLong(String.valueOf(second.get("workId")));
        assertThat(works.occupyingWork(authorId).id).isEqualTo(secondId);

        WorkModerationTicket appealing = tickets.byId(ticket.id);
        assertThatThrownBy(() -> call(SUPERVISOR, "POST", "/admin/appeals/" + ticket.id + "/handle",
                body("action", "overturn", "expectedStateVersion", appealing.stateVersion)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.detail()).isEqualTo("submission_slot_occupied");
                    assertThat(ex.code()).isEqualTo(ApiException.RULE_FORBIDDEN);
                });
        assertThat(works.byId(firstId).status).isEqualTo(Work.Status.APPEALING);
        assertThat(works.byId(secondId).status).isEqualTo(Work.Status.PENDING);
    }

    @Test
    void resubmitAfterRejectStillSeesPriorAppeal() throws Exception {
        Map<String, Object> published = call(authorId, "POST", "/works",
                body("mediaType", "image", "mediaKey", "media-1", "title", "先申", "body", "再提"));
        long workId = Long.parseLong(String.valueOf(published.get("workId")));
        WorkModerationTicket first = tickets.activeByWork(workId);
        call(REVIEWER, "POST", "/admin/moderation/" + first.id + "/handle",
                body("action", "reject", "expectedStateVersion", first.stateVersion, "reasonCode", "policy"));
        call(authorId, "POST", "/works/" + workId + "/appeal", body("text", "先申一次"));
        WorkModerationTicket appealing = tickets.byId(first.id);
        call(SUPERVISOR, "POST", "/admin/appeals/" + first.id + "/handle",
                body("action", "uphold", "expectedStateVersion", appealing.stateVersion));

        call(authorId, "PUT", "/works/" + workId + "/draft", body("title", "改过", "body", "再提"));
        call(authorId, "POST", "/works/" + workId + "/resubmit",
                body("contentVersion", 2, "idempotencyKey", "again"));
        WorkModerationTicket second = tickets.activeByWork(workId);
        call(REVIEWER, "POST", "/admin/moderation/" + second.id + "/handle",
                body("action", "reject", "expectedStateVersion", second.stateVersion, "reasonCode", "policy"));

        Map<String, Object> mine = call(authorId, "GET", "/works/" + workId + "/moderation", null);
        assertThat(mine).containsEntry("appealUsed", true).containsEntry("appealable", false);
        assertThatThrownBy(() -> call(authorId, "POST", "/works/" + workId + "/appeal",
                body("text", "第二次")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.detail()).isEqualTo("appeal_already_used"));
    }

    @Test
    void pendingCannotAppeal() throws Exception {
        Map<String, Object> published = call(authorId, "POST", "/works",
                body("mediaType", "image", "mediaKey", "media-1", "title", "待审", "body", "不能申"));
        long workId = Long.parseLong(String.valueOf(published.get("workId")));
        assertThatThrownBy(() -> call(authorId, "POST", "/works/" + workId + "/appeal",
                body("text", "还没判")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ModerationStateMachine.ERR_APPEAL_NOT_APPLICABLE);
                    assertThat(ex.detail()).isEqualTo("appeal_not_applicable");
                });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(long accountId, String method, String path, JsonObject body)
            throws Exception {
        String route = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        Map<String, String> query = new LinkedHashMap<>();
        if (path.contains("?")) {
            for (String part : path.substring(path.indexOf('?') + 1).split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    query.put(part.substring(0, eq), part.substring(eq + 1));
                }
            }
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
