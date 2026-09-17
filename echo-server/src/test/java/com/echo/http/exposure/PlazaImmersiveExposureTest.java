package com.echo.http.exposure;

import com.aengine.util.id.IDGenerator;
import com.echo.http.EchoApi;
import com.echo.http.RequestContext;
import com.echo.http.Router;
import com.echo.http.store.InMemoryEchoStore;
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

class PlazaImmersiveExposureTest {
    private final IDGenerator ids = new IDGenerator(61);
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
        api.setWorkStore(works);
        api.setFeedRequests(registry);
        router = api.routes(false);
        works.insert(work(authorId, "它最后一个下午", 10));
        works.insert(work(authorId, "球还在沙发底下", 20));
    }

    @Test
    void gridReqIdDoesNotCountUntilImmersiveSessionOpens() throws Exception {
        Map<String, Object> page = plaza(viewerId);
        String gridReqId = String.valueOf(page.get("reqId"));
        assertThat(gridReqId).isNotBlank();
        assertThat(registry.lookup(gridReqId).countsTowardExposure()).isFalse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        String workId = String.valueOf(items.get(0).get("id"));
        ExposureRecorder.Outcome gridOut = recorder.record(gridReqId, viewerId,
                List.of(item(workId)));
        assertThat(gridOut.accepted()).isZero();

        Map<String, Object> session = openImmersive(viewerId, gridReqId);
        String immersiveReqId = String.valueOf(session.get("reqId"));
        assertThat(session.get("countsTowardExposure")).isEqualTo(true);
        assertThat(immersiveReqId).isNotBlank().isNotEqualTo(gridReqId);
        assertThat(registry.lookup(immersiveReqId).countsTowardExposure()).isTrue();

        ExposureRecorder.Outcome out = recorder.record(immersiveReqId, viewerId,
                List.of(item(workId)));
        assertThat(out.accepted()).isEqualTo(1);
        assertThat(out.rejected()).isZero();
    }

    @Test
    void missingGridSnapshotOpensWithoutCounting() throws Exception {
        Map<String, Object> session = openImmersive(viewerId, "gone");
        assertThat(session.get("reqId")).isEqualTo("");
        assertThat(session.get("countsTowardExposure")).isEqualTo(false);
    }

    @Test
    void otherViewerCannotMintFromThisGridSnapshot() throws Exception {
        Map<String, Object> page = plaza(viewerId);
        String gridReqId = String.valueOf(page.get("reqId"));
        Map<String, Object> session = openImmersive(viewerId + 1, gridReqId);
        assertThat(session.get("countsTowardExposure")).isEqualTo(false);
        assertThat(session.get("reqId")).isEqualTo("");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> plaza(long viewer) throws Exception {
        Router.Match match = router.match("GET", "/plaza");
        Object result = match.handle(new RequestContext(
                "GET", match.pathParams, Map.of("limit", "20"), null, viewer, Map.of()));
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
