package com.echo.http.exposure;

import com.aengine.util.id.IDGenerator;
import com.echo.http.EchoApi;
import com.echo.http.RequestContext;
import com.echo.http.Router;
import com.echo.http.WorksApi;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.http.work.ResourceStore;
import com.echo.http.work.Work;
import com.echo.http.work.WorkStore;
import com.echo.infra.corpus.InMemoryTrainingCorpus;
import com.echo.infra.llm.MockLlmClient;
import com.echo.infra.vision.StubVisionClient;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorHomeImmersiveExposureTest {
    private final IDGenerator ids = new IDGenerator(62);
    private final InMemoryEchoStore accounts = new InMemoryEchoStore();
    private final WorkStore works = new WorkStore(null);
    private FeedRequestRegistry registry;
    private ExposureRecorder recorder;
    private Router router;
    private long authorId;
    private long viewerId;

    @BeforeEach
    void setUp() {
        authorId = ids.nextId();
        viewerId = ids.nextId();
        ExposureConfig config = ExposureConfig.forTest();
        registry = new FeedRequestRegistry(config);
        recorder = new ExposureRecorder(config, registry, null, ids);
        EchoApi api = new EchoApi(accounts, ids, new MockLlmClient(), new StubVisionClient(),
                null, new InMemoryTrainingCorpus());
        api.setFeedRequests(registry);
        WorksApi worksApi = new WorksApi(works, accounts, null, new ResourceStore(null), null, ids);
        worksApi.setFeedRequests(registry);
        router = api.routes(false);
        worksApi.register(router);
        works.insert(work(authorId, "它最后一个下午", 10));
    }

    @Test
    void otherViewerWallIssuesGridSnapshotThenImmersiveCounts() throws Exception {
        Map<String, Object> page = userWorks(viewerId, authorId);
        String wallReqId = String.valueOf(page.get("reqId"));
        assertThat(wallReqId).isNotBlank();
        assertThat(registry.lookup(wallReqId).countsTowardExposure()).isFalse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        String workId = String.valueOf(items.get(0).get("id"));
        assertThat(recorder.record(wallReqId, viewerId, List.of(item(workId))).accepted()).isZero();

        Map<String, Object> session = openImmersive(viewerId, wallReqId);
        String immersiveReqId = String.valueOf(session.get("reqId"));
        assertThat(session.get("countsTowardExposure")).isEqualTo(true);
        assertThat(registry.lookup(immersiveReqId).countsTowardExposure()).isTrue();
        assertThat(recorder.record(immersiveReqId, viewerId, List.of(item(workId))).accepted()).isEqualTo(1);
    }

    @Test
    void ownWallDoesNotIssueSnapshot() throws Exception {
        Map<String, Object> page = userWorks(authorId, authorId);
        assertThat(page.get("reqId")).isNull();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> userWorks(long viewer, long author) throws Exception {
        Router.Match match = router.match("GET", "/users/" + author + "/works");
        Object result = match.handle(new RequestContext(
                "GET", match.pathParams, Map.of(), null, viewer, Map.of()));
        return (Map<String, Object>) result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> openImmersive(long viewer, String fromReqId) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("fromReqId", fromReqId);
        Router.Match match = router.match("POST", "/plaza/immersive");
        Object result = match.handle(new RequestContext(
                "POST", match.pathParams, Map.of(), body, viewer, Map.of()));
        return (Map<String, Object>) result;
    }

    private static ExposureRecorder.Item item(String workId) {
        return new ExposureRecorder.Item(workId, 0, 1200L, System.currentTimeMillis());
    }

    private Work work(long author, String title, long publishedAt) {
        Work w = new Work();
        w.id = ids.nextId();
        w.authorId = author;
        w.mediaType = Work.MediaType.IMAGE;
        w.mediaKey = "media-1";
        w.title = title;
        w.body = title;
        w.status = Work.Status.PUBLIC;
        w.visibility = "public";
        w.publishedAt = publishedAt;
        w.createdAt = publishedAt;
        w.updatedAt = publishedAt;
        return w;
    }
}
