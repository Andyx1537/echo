package com.echo.http.work;

import com.aengine.util.id.IDGenerator;
import com.echo.http.EchoApi;
import com.echo.http.RequestContext;
import com.echo.http.Router;
import com.echo.http.governance.BlockService;
import com.echo.http.governance.BlockStore;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.infra.corpus.InMemoryTrainingCorpus;
import com.echo.infra.llm.MockLlmClient;
import com.echo.infra.vision.StubVisionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlazaWorkReadTest {
    private final IDGenerator ids = new IDGenerator(44);
    private final InMemoryEchoStore accounts = new InMemoryEchoStore();
    private final WorkStore works = new WorkStore(null);
    private Router router;
    private BlockService blocks;
    private long authorId;
    private long viewerId;

    @BeforeEach
    void setUp() {
        authorId = ids.nextId();
        viewerId = ids.nextId();
        EchoApi api = new EchoApi(accounts, ids, new MockLlmClient(), new StubVisionClient(),
                null, new InMemoryTrainingCorpus());
        api.setWorkStore(works);
        blocks = new BlockService(new BlockStore(null), ids);
        api.setBlockService(blocks);
        router = api.routes(false);
    }

    @Test
    void plazaListsOnlyPublicWorksAndHidesBlockedAuthors() throws Exception {
        works.insert(work(authorId, Work.Status.PUBLIC, "公开的", 10));
        works.insert(work(authorId, Work.Status.PENDING, "还在审", 20));
        Map<String, Object> page = plaza(viewerId);
        List<Map<String, Object>> items = items(page);
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("title", "公开的")
                .containsEntry("sourceType", "user_upload")
                .containsEntry("aiGenerated", false)
                .doesNotContainKey("sourceCardId")
                .doesNotContainKey("status");

        blocks.block(authorId, viewerId);
        assertThat(items(plaza(viewerId))).isEmpty();
    }

    @Test
    void anonymousViewerGetsAFixedBatchOfAboutThirty() throws Exception {
        for (int i = 0; i < 35; i++) {
            works.insert(work(authorId, Work.Status.PUBLIC, "公开-" + i, 100 + i));
        }
        putViewer(viewerId, true);
        List<Map<String, Object>> first = items(plaza(viewerId, 50));
        assertThat(first).hasSize(30);
        assertThat(first.get(0)).doesNotContainKey("sourceCardId");

        List<Map<String, Object>> again = items(plaza(viewerId, 50));
        assertThat(again.stream().map(item -> item.get("id")).toList())
                .isEqualTo(first.stream().map(item -> item.get("id")).toList());

        long bound = ids.nextId();
        putViewer(bound, false);
        assertThat(items(plaza(bound, 50))).hasSize(35);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> plaza(long viewer) throws Exception {
        return plaza(viewer, 20);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> plaza(long viewer, int limit) throws Exception {
        Router.Match match = router.match("GET", "/plaza");
        Object result = match.handle(new RequestContext(
                "GET", match.pathParams, Map.of("limit", String.valueOf(limit)), null, viewer, Map.of()));
        return (Map<String, Object>) result;
    }

    private void putViewer(long accountId, boolean guest) {
        AccountProfile profile = new AccountProfile();
        profile.accountId = accountId;
        profile.deviceId = "dev-" + accountId;
        profile.nickname = "u" + accountId;
        profile.guest = guest;
        accounts.putProfile(profile);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private Work work(long author, String status, String title, long publishedAt) {
        Work w = new Work();
        w.id = ids.nextId();
        w.authorId = author;
        w.mediaType = Work.MediaType.IMAGE;
        w.mediaKey = "media-1";
        w.title = title;
        w.body = title;
        w.status = status;
        w.visibility = "public";
        w.publishedAt = publishedAt;
        w.createdAt = publishedAt;
        w.updatedAt = publishedAt;
        return w;
    }
}
