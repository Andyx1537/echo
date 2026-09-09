package com.echo.http.auth;

import com.aengine.util.id.IDGenerator;
import com.echo.http.ApiException;
import com.echo.http.EchoApi;
import com.echo.http.RequestContext;
import com.echo.http.Router;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.infra.corpus.InMemoryTrainingCorpus;
import com.echo.infra.llm.MockLlmClient;
import com.echo.infra.vision.StubVisionClient;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PH-26: unverified legacy entry points are permanently retired. */
class RetiredAuthApiTest {
    @Test
    void oldGuestAndBindReturnGone() {
        EchoApi api = new EchoApi(new InMemoryEchoStore(), new IDGenerator(61), new MockLlmClient(),
                new StubVisionClient(), null, new InMemoryTrainingCorpus());
        Router router = api.routes(false);
        for (String path : new String[]{"/auth/guest", "/auth/bind"}) {
            Router.Match match = router.match("POST", path);
            assertThat(match.isPublic()).isTrue();
            RequestContext ctx = new RequestContext("POST", match.pathParams, Map.of(), new JsonObject(), 1L);
            assertThatThrownBy(() -> match.handle(ctx)).isInstanceOf(ApiException.class)
                    .satisfies(e -> {
                        assertThat(((ApiException) e).code()).isEqualTo(ApiException.GONE);
                        assertThat(((ApiException) e).detail()).isEqualTo("endpoint_retired");
                    });
        }
    }
}
