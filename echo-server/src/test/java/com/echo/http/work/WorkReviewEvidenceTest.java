package com.echo.http.work;

import com.aengine.util.id.IDGenerator;
import com.echo.http.ApiException;
import com.echo.http.RequestContext;
import com.echo.http.Router;
import com.echo.http.WorksApi;
import com.echo.http.model.ModerationModels.CardStatus;
import com.echo.http.model.ModerationModels.MemoryCard;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.http.store.InMemoryModerationStore;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkReviewEvidenceTest {
    private final IDGenerator ids = new IDGenerator(43);
    private final InMemoryEchoStore accounts = new InMemoryEchoStore();
    private final WorkStore store = new WorkStore(null);
    private final ResourceStore resources = new ResourceStore(null);
    private final WorkReviewEvidenceStore evidence = new WorkReviewEvidenceStore(null);
    private final InMemoryModerationStore cards = new InMemoryModerationStore(ids);
    private Router router;
    private long accountId;

    @BeforeEach
    void setUp() {
        accountId = ids.nextId();
        AccountProfile profile = new AccountProfile();
        profile.accountId = accountId;
        profile.deviceId = "work-review-evidence";
        profile.guest = false;
        accounts.putProfile(profile);
        resources.record("media-1", accountId, "media-1", "image/jpeg", 12, 1);
        WorksApi api = new WorksApi(store, accounts, null, resources, null, ids);
        api.setCardStore(cards);
        api.setReviewEvidenceStore(evidence);
        router = new Router();
        api.register(router);
    }

    @Test
    void matchingEvidencePublishesPublicAndConsumesTheTicket() throws Exception {
        long cardId = ids.nextId();
        cards.putCard(ownCard(cardId, CardStatus.PUBLIC));
        long evidenceId = ids.nextId();
        evidence.put(WorkReviewEvidence.passed(evidenceId, cardId, accountId, reviewHash("原标题", "原文"),
                System.currentTimeMillis()));

        Map<String, Object> first = publish(cardId, "原标题", "原文", evidenceId);
        assertThat(first).containsEntry("status", Work.Status.PUBLIC)
                .containsEntry("reviewMode", WorkReviewDecision.MODE_REUSED)
                .containsEntry("message", "已经在广场上了。");
        Work created = store.byId(Long.parseLong(String.valueOf(first.get("workId"))));
        assertThat(created.status).isEqualTo(Work.Status.PUBLIC);
        assertThat(created.reviewEvidenceId).isEqualTo(evidenceId);
        assertThat(created.reviewedAt).isNotNull();
        assertThat(store.occupyingWork(accountId)).isNull();
        assertThat(evidence.byId(evidenceId).consumedByWorkId).isEqualTo(created.id);

        int before = store.worksOfAuthor(accountId, true, 20).size();
        assertThatThrownBy(() -> publish(cardId, "原标题", "原文", evidenceId))
                .isInstanceOf(ApiException.class);
        assertThat(store.worksOfAuthor(accountId, true, 20)).hasSize(before);
    }

    @Test
    void changedOrExpiredEvidenceFallsToFullReview() throws Exception {
        long changedCard = ids.nextId();
        cards.putCard(ownCard(changedCard, CardStatus.PUBLIC));
        evidence.put(WorkReviewEvidence.passed(ids.nextId(), changedCard, accountId,
                reviewHash("原标题", "原文"), System.currentTimeMillis()));
        Map<String, Object> changed = publish(changedCard, "改过的标题", "原文", null);
        assertThat(changed).containsEntry("status", Work.Status.PENDING)
                .containsEntry("reviewMode", WorkReviewDecision.MODE_FULL)
                .containsEntry("reasonCode", "evidence_content_mismatch");
        long changedId = Long.parseLong(String.valueOf(changed.get("workId")));
        assertThat(store.byId(changedId).status).isEqualTo(Work.Status.PENDING);
        store.softDelete(changedId, accountId, "free-slot", 2);

        long expiredCard = ids.nextId();
        cards.putCard(ownCard(expiredCard, CardStatus.PUBLIC));
        WorkReviewEvidence expired = WorkReviewEvidence.passed(ids.nextId(), expiredCard, accountId,
                reviewHash("还在", "没改"), System.currentTimeMillis());
        expired.expiresAt = System.currentTimeMillis() - 1;
        evidence.put(expired);
        Map<String, Object> stale = publish(expiredCard, "还在", "没改", expired.reviewEvidenceId);
        assertThat(stale).containsEntry("status", Work.Status.PENDING)
                .containsEntry("reviewMode", WorkReviewDecision.MODE_FULL)
                .containsEntry("reasonCode", "evidence_expired");
        store.softDelete(Long.parseLong(String.valueOf(stale.get("workId"))), accountId, "free-slot", 3);

        long missingCard = ids.nextId();
        cards.putCard(ownCard(missingCard, CardStatus.PUBLIC));
        Map<String, Object> missing = publish(missingCard, "原标题", "原文", null);
        assertThat(missing).containsEntry("status", Work.Status.PENDING)
                .containsEntry("reasonCode", "evidence_missing");
    }

    @Test
    void hardFailuresDoNotCreateAWork() {
        long consumedCard = ids.nextId();
        cards.putCard(ownCard(consumedCard, CardStatus.PUBLIC));
        WorkReviewEvidence consumed = WorkReviewEvidence.passed(ids.nextId(), consumedCard, accountId,
                reviewHash("原标题", "原文"), System.currentTimeMillis());
        consumed.consumedByWorkId = 99L;
        evidence.put(consumed);
        assertThatThrownBy(() -> publish(consumedCard, "原标题", "原文", consumed.reviewEvidenceId))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.detail()).isEqualTo("evidence_consumed");
                    assertThat(ex.data()).containsEntry("workCreated", false)
                            .containsEntry("reviewMode", WorkReviewDecision.MODE_NONE);
                });
        assertThat(store.publishedFromCard(consumedCard)).isFalse();

        long goneCard = ids.nextId();
        cards.putCard(ownCard(goneCard, CardStatus.TAKENDOWN));
        assertThatThrownBy(() -> publish(goneCard, "原标题", "原文", null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.detail()).isEqualTo("source_unavailable"));
        assertThat(store.publishedFromCard(goneCard)).isFalse();
    }

    @Test
    void userUploadStaysPendingWithoutEvidence() throws Exception {
        Map<String, Object> result = publish(null, "自制", "上传", null);
        assertThat(result).containsEntry("status", Work.Status.PENDING)
                .containsEntry("reviewMode", WorkReviewDecision.MODE_FULL)
                .containsEntry("reasonCode", "user_upload")
                .containsEntry("message", "已提交，过一会儿就能在广场看到它了。");
        assertThat(store.occupyingWork(accountId).status).isEqualTo(Work.Status.PENDING);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publish(Long cardId, String title, String body, Long evidenceId)
            throws Exception {
        JsonObject json = body(
                "mediaType", Work.MediaType.IMAGE,
                "mediaKey", "media-1",
                "title", title,
                "body", body,
                "visibility", "public");
        if (cardId != null) {
            json.addProperty("sourceCardId", String.valueOf(cardId));
        }
        if (evidenceId != null) {
            json.addProperty("reviewEvidenceId", String.valueOf(evidenceId));
        }
        Router.Match match = router.match("POST", "/works");
        Object result = match.handle(new RequestContext("POST", match.pathParams, Map.of(), json, accountId, Map.of()));
        return (Map<String, Object>) result;
    }

    private MemoryCard ownCard(long cardId, String status) {
        MemoryCard card = new MemoryCard();
        card.id = cardId;
        card.ownerId = accountId;
        card.coverKey = "media-1";
        card.title = "原标题";
        card.body = "原文";
        card.status = status;
        card.originType = "user";
        return card;
    }

    private String reviewHash(String title, String body) {
        Work w = new Work();
        w.mediaType = Work.MediaType.IMAGE;
        w.mediaKey = "media-1";
        w.title = title;
        w.body = body;
        w.aiGenerated = false;
        return WorkContent.reviewHash(w);
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
