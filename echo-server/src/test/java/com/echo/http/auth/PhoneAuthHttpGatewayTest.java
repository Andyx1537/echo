package com.echo.http.auth;

import com.aengine.util.id.IDGenerator;
import com.echo.http.ApiException;
import com.echo.http.EchoApi;
import com.echo.http.HttpGateway;
import com.echo.http.Router;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.infra.corpus.InMemoryTrainingCorpus;
import com.echo.infra.llm.MockLlmClient;
import com.echo.infra.persistence.PgDb;
import com.echo.infra.vision.StubVisionClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Phone account resolution through the real JDK HTTP server and socket boundary. */
class PhoneAuthHttpGatewayTest {
    private static final String PHONE = "+8613800000301";
    private static PgDb db;

    private StubSmsProvider sms;
    private HttpGateway gateway;
    private HttpClient client;
    private String baseUrl;

    @BeforeAll
    static void connect() {
        String url = System.getenv("ECHO_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "设置 ECHO_TEST_PG_URL 后运行真实 Auth HTTP/PG 测试");
        Properties properties = new Properties();
        properties.setProperty("db.name", "echo");
        properties.setProperty("jdbcUrl", url);
        properties.setProperty("driverClassName", "org.postgresql.Driver");
        properties.setProperty("username", System.getenv().getOrDefault("ECHO_TEST_PG_USER", "echo"));
        String password = System.getenv("ECHO_TEST_PG_PASSWORD");
        if (password != null) properties.setProperty("password", password);
        properties.setProperty("maximumPoolSize", "6");
        db = new PgDb(properties);
    }

    @AfterAll
    static void closeDb() {
        if (db != null) db.shutdown();
    }

    @BeforeEach
    void startGateway() throws Exception {
        for (String table : new String[]{"t_auth_idempotency", "t_auth_rate_event", "t_auth_audit",
                "t_phone_resolution", "t_phone_challenge", "t_phone_credential", "t_auth_session",
                "t_device_credential", "t_account_profile", "t_account"}) {
            db.update("DELETE FROM \"" + table + "\"");
        }
        startGatewayInstance();
    }

    private void startGatewayInstance() throws Exception {
        startGatewayInstance(false);
    }

    private void startGatewayInstance(boolean fixedCode) throws Exception {
        sms = new StubSmsProvider();
        PgAuthService auth = new PgAuthService(db, new IDGenerator(58),
                "test-http-auth-secret-that-is-longer-than-32-characters", fixedCode ? new DevelopmentSmsProvider() : sms,
                ContinuationPolicy.noneOnly(),
                Clock.fixed(Instant.parse("2026-09-07T02:30:00Z"), ZoneOffset.UTC));
        InMemoryEchoStore store = new InMemoryEchoStore();
        EchoApi api = new EchoApi(store, new IDGenerator(59), new MockLlmClient(),
                new StubVisionClient(), null, new InMemoryTrainingCorpus());
        Router router = api.routes(false);
        new AuthApi(auth).register(router);
        gateway = new HttpGateway(0, router, store, null, null, null, null, auth);
        gateway.start();
        client = HttpClient.newHttpClient();
        baseUrl = "http://127.0.0.1:" + gateway.localPort() + HttpGateway.BASE_PATH;
    }

    @AfterEach
    void stopGateway() {
        if (gateway != null) gateway.stop();
    }

    @Test
    void fixedFourDigitCodeBindsAndSwitchesThroughHttp() throws Exception {
        gateway.stop();
        startGatewayInstance(true);
        String owner = null;
        for (int i = 0; i < 2; i++) {
            Response device = post("/auth/device/session",
                    "{\"bootstrapNonce\":\"fixed-code-bootstrap-abcdefghijklmnopqrstuvwxyz-" + i + "\"}", null, "fixed-device-" + i);
            assertSuccess(device);
            JsonObject issued = device.json.getAsJsonObject("data");
            String token = issued.get("sessionToken").getAsString();
            Response challenge = post("/auth/phone/challenges", phoneBody("+8600000000000"), token, "fixed-challenge-" + i);
            assertSuccess(challenge);
            String id = challenge.json.getAsJsonObject("data").get("challengeId").getAsString();
            Response wrong = post("/auth/phone/challenges/" + id + "/verify", "{\"code\":\"1111\"}", token, "fixed-wrong-" + i);
            assertThat(wrong.json.get("detail").getAsString()).isEqualTo("code_invalid");
            Response verified = post("/auth/phone/challenges/" + id + "/verify", "{\"code\":\"9999\"}", token, "fixed-verify-" + i);
            assertSuccess(verified);
            JsonObject resolution = verified.json.getAsJsonObject("data");
            assertThat(resolution.get("resolution").getAsString()).isEqualTo(i == 0 ? "bind_current" : "switch_existing");
            Response confirmed = post("/auth/phone/resolutions/" + resolution.get("resolutionToken").getAsString() + "/confirm", "{}", token, "fixed-confirm-" + i);
            assertSuccess(confirmed);
            String account = confirmed.json.getAsJsonObject("data").get("accountId").getAsString();
            if (i == 0) owner = account;
            assertThat(account).isEqualTo(owner);
        }
    }

    @Test
    void phoneMainlineAndIdempotencyCrossTheRealGateway() throws Exception {
        String nonce = "http-bootstrap-nonce-abcdefghijklmnopqrstuvwxyz";
        Response issued = post("/auth/device/session",
                "{\"bootstrapNonce\":\"" + nonce + "\"}", null, "device-fixed");
        assertSuccess(issued);
        JsonObject issuedData = issued.json.getAsJsonObject("data");
        String anonymousToken = issuedData.get("sessionToken").getAsString();
        String deviceCredential = issuedData.get("deviceCredential").getAsString();

        String restoreBody = "{\"deviceCredential\":\"" + deviceCredential + "\"}";
        Response restored = post("/auth/device/session", restoreBody, null, "device-restore-fixed");
        assertSuccess(restored);
        Response deviceReplay = post("/auth/device/session", restoreBody, null, "device-restore-fixed");
        assertThat(deviceReplay.status).isEqualTo(200);
        assertThat(deviceReplay.json).isEqualTo(restored.json);
        assertError(post("/auth/device/session",
                        "{\"deviceCredential\":\"" + deviceCredential + "\","
                                + "\"bootstrapNonce\":\"http-bootstrap-nonce-different-abcdefghijklmnopqrstuvwxyz\"}",
                        null, "device-restore-fixed"),
                409, ApiException.RULE_FORBIDDEN, "device_session_idempotency_conflict");

        String challengeBody = """
                {"phone":"+8613800000301","purpose":"login_or_bind","continuation":{"intent":"none"}}
                """;
        assertError(post("/auth/phone/challenges", challengeBody, null, "challenge-fixed"),
                401, ApiException.UNAUTHORIZED, "missing bearer token");
        assertError(post("/auth/phone/challenges", challengeBody, "not-a-session", "challenge-fixed"),
                401, ApiException.UNAUTHORIZED, "invalid token");

        Response challenged = post("/auth/phone/challenges", challengeBody, anonymousToken, "challenge-fixed");
        assertSuccess(challenged);
        Response challengeReplay = post("/auth/phone/challenges", challengeBody, anonymousToken, "challenge-fixed");
        assertThat(challengeReplay.json).isEqualTo(challenged.json);
        assertError(post("/auth/phone/challenges",
                        challengeBody.replace(PHONE, "+8613800000302"), anonymousToken, "challenge-fixed"),
                409, ApiException.RULE_FORBIDDEN, "idempotency_conflict");

        String challengeId = challenged.json.getAsJsonObject("data").get("challengeId").getAsString();
        Response verified = post("/auth/phone/challenges/" + challengeId + "/verify",
                "{\"code\":\"" + sms.codeFor(PHONE) + "\"}", anonymousToken, "verify-fixed");
        assertSuccess(verified);
        assertThat(verified.json.getAsJsonObject("data").get("resolution").getAsString())
                .isEqualTo("bind_current");
        String resolutionToken = verified.json.getAsJsonObject("data").get("resolutionToken").getAsString();

        Response confirmed = post("/auth/phone/resolutions/" + resolutionToken + "/confirm",
                "{}", anonymousToken, "confirm-fixed");
        assertSuccess(confirmed);
        assertThat(confirmed.json.getAsJsonObject("data").get("phoneBound").getAsBoolean()).isTrue();
        Response confirmReplay = post("/auth/phone/resolutions/" + resolutionToken + "/confirm",
                "{}", anonymousToken, "confirm-fixed");
        assertThat(confirmReplay.json).isEqualTo(confirmed.json);
    }

    @Test
    void switchExistingKeepsTheAnonymousAccountAndIssuesControlledRecovery() throws Exception {
        Response targetDevice = post("/auth/device/session",
                "{\"bootstrapNonce\":\"switch-target-bootstrap-nonce-abcdefghijklmnopqrstuvwxyz\"}",
                null, "switch-target-device");
        JsonObject targetDeviceData = targetDevice.json.getAsJsonObject("data");
        String targetAnonymousToken = targetDeviceData.get("sessionToken").getAsString();
        Response targetChallenge = post("/auth/phone/challenges", phoneBody(PHONE),
                targetAnonymousToken, "switch-target-challenge");
        String targetChallengeId = targetChallenge.json.getAsJsonObject("data").get("challengeId").getAsString();
        Response targetVerified = post("/auth/phone/challenges/" + targetChallengeId + "/verify",
                "{\"code\":\"" + sms.codeFor(PHONE) + "\"}", targetAnonymousToken, "switch-target-verify");
        String targetResolution = targetVerified.json.getAsJsonObject("data")
                .get("resolutionToken").getAsString();
        Response targetConfirmed = post("/auth/phone/resolutions/" + targetResolution + "/confirm",
                "{}", targetAnonymousToken, "switch-target-confirm");
        JsonObject targetAccount = targetConfirmed.json.getAsJsonObject("data");
        String targetAccountId = targetAccount.get("accountId").getAsString();
        String firstTargetBoundToken = targetAccount.get("sessionToken").getAsString();

        Response sourceDevice = post("/auth/device/session",
                "{\"bootstrapNonce\":\"switch-source-bootstrap-nonce-abcdefghijklmnopqrstuvwxyz\"}",
                null, "switch-source-device");
        JsonObject sourceDeviceData = sourceDevice.json.getAsJsonObject("data");
        String sourceAccountId = sourceDeviceData.get("accountId").getAsString();
        String sourceToken = sourceDeviceData.get("sessionToken").getAsString();
        Response sourceChallenge = post("/auth/phone/challenges", phoneBody(PHONE),
                sourceToken, "switch-source-challenge");
        String sourceChallengeId = sourceChallenge.json.getAsJsonObject("data").get("challengeId").getAsString();
        Response sourceVerified = post("/auth/phone/challenges/" + sourceChallengeId + "/verify",
                "{\"code\":\"" + sms.codeFor(PHONE) + "\"}", sourceToken, "switch-source-verify");
        JsonObject resolution = sourceVerified.json.getAsJsonObject("data");
        assertThat(resolution.get("resolution").getAsString()).isEqualTo("switch_existing");

        Response switched = post("/auth/phone/resolutions/" + resolution.get("resolutionToken").getAsString()
                + "/confirm", "{}", sourceToken, "switch-source-confirm");
        assertSuccess(switched);
        JsonObject switchedData = switched.json.getAsJsonObject("data");
        assertThat(switchedData.get("accountId").getAsString()).isEqualTo(targetAccountId);
        assertThat(switchedData.get("phoneBound").getAsBoolean()).isTrue();
        assertThat(switchedData.get("sessionToken").getAsString()).isNotEqualTo(firstTargetBoundToken);
        assertThat(switchedData.get("previousAnonymousCredentialDisposition").getAsString())
                .isEqualTo("retained_as_recovery");
        assertThat(switchedData.get("deviceCredential").isJsonNull()).isTrue();
        JsonObject recovery = switchedData.getAsJsonObject("anonymousRecovery");
        assertThat(recovery).isNotNull();
        String recoveryCredential = recovery.get("recoveryCredential").getAsString();
        assertThat(recoveryCredential).isNotBlank();

        assertError(post("/auth/phone/challenges", phoneBody("+8613800000398"),
                        sourceToken, "switch-old-bearer"),
                401, ApiException.UNAUTHORIZED, "invalid token");
        String newTargetToken = switchedData.get("sessionToken").getAsString();
        assertError(post("/auth/phone/challenges", phoneBody("+8613800000399"),
                        newTargetToken, "switch-new-bearer"),
                409, ApiException.RULE_FORBIDDEN, "resolution_session_mismatch");
        assertError(post("/auth/device/session",
                        "{\"deviceCredential\":\"" + recoveryCredential + "\"}",
                        null, "switch-recovery-generic"),
                409, ApiException.RULE_FORBIDDEN, "device_credential_recovery_required");
        Response recovered = post("/auth/account/recovery/session",
                "{\"recoveryCredential\":\"" + recoveryCredential + "\"}",
                null, "switch-recovery-wake");
        assertSuccess(recovered);
        JsonObject recoveredData = recovered.json.getAsJsonObject("data");
        assertThat(recoveredData.get("accountId").getAsString()).isEqualTo(sourceAccountId);
        assertThat(recoveredData.get("phoneBound").getAsBoolean()).isFalse();
        assertThat(recoveredData.get("deviceCredentialAction").getAsString()).isEqualTo("recovered");
        assertError(post("/auth/account/recovery/session",
                        "{\"recoveryCredential\":\"" + recoveryCredential + "\",\"accountId\":\"" + sourceAccountId + "\"}",
                        null, "switch-recovery-account-id"),
                400, ApiException.BAD_PARAM, "continuation_invalid");

        long sourceId = Long.parseLong(sourceAccountId);
        assertThat(count("SELECT COUNT(*) AS n FROM \"t_account\" a JOIN \"t_account_profile\" p "
                + "ON p.\"accountId\"=a.\"id\" WHERE a.\"id\"=? AND p.\"guest\"=1", sourceId))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) AS n FROM \"t_phone_credential\" WHERE \"accountId\"=?", sourceId))
                .isZero();
    }

    @Test
    void confirmCanReplayAfterClientDisconnectsBeforeReadingTheCommittedResponse() throws Exception {
        Response device = post("/auth/device/session",
                "{\"bootstrapNonce\":\"disconnect-bootstrap-nonce-abcdefghijklmnopqrstuvwxyz\"}",
                null, "disconnect-device");
        JsonObject deviceData = device.json.getAsJsonObject("data");
        String accountId = deviceData.get("accountId").getAsString();
        String anonymousToken = deviceData.get("sessionToken").getAsString();

        Response challenged = post("/auth/phone/challenges", phoneBody("+8613800000310"),
                anonymousToken, "disconnect-challenge");
        String challengeId = challenged.json.getAsJsonObject("data").get("challengeId").getAsString();
        Response verified = post("/auth/phone/challenges/" + challengeId + "/verify",
                "{\"code\":\"" + sms.codeFor("+8613800000310") + "\"}",
                anonymousToken, "disconnect-verify");
        String resolutionToken = verified.json.getAsJsonObject("data").get("resolutionToken").getAsString();

        sendCompleteRequestAndDisconnect("/auth/phone/resolutions/" + resolutionToken + "/confirm",
                anonymousToken, "disconnect-confirm");
        long account = Long.parseLong(accountId);
        awaitCount("SELECT COUNT(*) AS n FROM \"t_phone_credential\" WHERE \"accountId\"=?", account, 1);

        Response recovered = post("/auth/phone/resolutions/" + resolutionToken + "/confirm",
                "{}", anonymousToken, "disconnect-confirm");
        assertSuccess(recovered);
        JsonObject recoveredData = recovered.json.getAsJsonObject("data");
        assertThat(recoveredData.get("accountId").getAsString()).isEqualTo(accountId);
        assertThat(recoveredData.get("phoneBound").getAsBoolean()).isTrue();
        Response replayed = post("/auth/phone/resolutions/" + resolutionToken + "/confirm",
                "{}", anonymousToken, "disconnect-confirm");
        assertThat(replayed.json).isEqualTo(recovered.json);

        assertThat(count("SELECT COUNT(*) AS n FROM \"t_phone_credential\" WHERE \"accountId\"=?", account))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) AS n FROM \"t_auth_session\" WHERE \"accountId\"=? "
                + "AND \"kind\"='bound' AND \"status\"='active'", account)).isEqualTo(1);
        assertError(post("/auth/phone/resolutions/" + resolutionToken + "/confirm",
                        "{}", anonymousToken, "disconnect-confirm-different"),
                409, ApiException.RULE_FORBIDDEN, "resolution_used");
    }

    @Test
    void historicalCredentialsRemainUsableAfterServiceAndGatewayRestart() throws Exception {
        Response anonymous = post("/auth/device/session",
                "{\"bootstrapNonce\":\"historic-anonymous-bootstrap-nonce-abcdefghijklmnopqrstuvwxyz\"}",
                null, "historic-anonymous-device");
        JsonObject anonymousData = anonymous.json.getAsJsonObject("data");
        long anonymousAccountId = anonymousData.get("accountId").getAsLong();
        String anonymousToken = anonymousData.get("sessionToken").getAsString();
        String anonymousDeviceCredential = anonymousData.get("deviceCredential").getAsString();

        Response binding = post("/auth/device/session",
                "{\"bootstrapNonce\":\"historic-binding-bootstrap-nonce-abcdefghijklmnopqrstuvwxyz\"}",
                null, "historic-binding-device");
        JsonObject bindingData = binding.json.getAsJsonObject("data");
        long boundAccountId = bindingData.get("accountId").getAsLong();
        String bindingAnonymousToken = bindingData.get("sessionToken").getAsString();
        String revokedAfterBindCredential = bindingData.get("deviceCredential").getAsString();
        String historicPhone = "+8613800000320";
        Response challenged = post("/auth/phone/challenges", phoneBody(historicPhone),
                bindingAnonymousToken, "historic-bind-challenge");
        String challengeId = challenged.json.getAsJsonObject("data").get("challengeId").getAsString();
        Response verified = post("/auth/phone/challenges/" + challengeId + "/verify",
                "{\"code\":\"" + sms.codeFor(historicPhone) + "\"}", bindingAnonymousToken,
                "historic-bind-verify");
        String resolutionToken = verified.json.getAsJsonObject("data").get("resolutionToken").getAsString();
        Response confirmed = post("/auth/phone/resolutions/" + resolutionToken + "/confirm",
                "{}", bindingAnonymousToken, "historic-bind-confirm");
        String boundToken = confirmed.json.getAsJsonObject("data").get("sessionToken").getAsString();

        long threeYearsAgo = Instant.parse("2023-09-07T00:00:00Z").toEpochMilli();
        ageAccount(anonymousAccountId, threeYearsAgo);
        ageAccount(boundAccountId, threeYearsAgo);

        gateway.stop();
        gateway = null;
        startGatewayInstance();

        Response anonymousStillAuthenticated = post("/auth/phone/challenges",
                phoneBody("+8613800000321"), anonymousToken, "historic-anonymous-challenge");
        assertSuccess(anonymousStillAuthenticated);

        Response restored = post("/auth/device/session",
                "{\"deviceCredential\":\"" + anonymousDeviceCredential + "\"}",
                null, "historic-anonymous-restore");
        assertSuccess(restored);
        assertThat(restored.json.getAsJsonObject("data").get("accountId").getAsLong())
                .isEqualTo(anonymousAccountId);

        assertError(post("/auth/phone/challenges", phoneBody("+8613800000322"),
                        boundToken, "historic-bound-session"),
                409, ApiException.RULE_FORBIDDEN, "resolution_session_mismatch");

        Response rotated = post("/auth/device/session",
                "{\"deviceCredential\":\"" + revokedAfterBindCredential + "\"}",
                null, "historic-revoked-device");
        assertSuccess(rotated);
        JsonObject rotatedData = rotated.json.getAsJsonObject("data");
        assertThat(rotatedData.get("deviceCredentialAction").getAsString()).isEqualTo("rotated_after_bind");
        assertThat(rotatedData.get("accountId").getAsLong()).isNotEqualTo(boundAccountId);
    }

    @Test
    void retiredEntryPointsAlwaysReturnTheSameGoneEnvelope() throws Exception {
        Response issued = post("/auth/device/session",
                "{\"bootstrapNonce\":\"legacy-http-bootstrap-nonce-abcdefghijklmnopqrstuvwxyz\"}",
                null, "legacy-device");
        String bearer = issued.json.getAsJsonObject("data").get("sessionToken").getAsString();
        for (String path : new String[]{"/auth/guest", "/auth/bind"}) {
            Response withoutBearer = post(path, "{}", null, null);
            Response withBearer = post(path, "{}", bearer, null);
            assertError(withoutBearer, 410, ApiException.GONE, "endpoint_retired");
            assertError(withBearer, 410, ApiException.GONE, "endpoint_retired");
            assertThat(withBearer.json).isEqualTo(withoutBearer.json);
        }
    }

    @Test
    void matchedRouteTemplateNeverContainsTheResolutionCredential() {
        Router router = new Router();
        router.addPublic("POST", "/auth/phone/resolutions/:resolutionToken/confirm", context -> null);
        String secret = "sensitive-resolution-token-that-must-not-be-logged";
        Router.Match match = router.match("POST", "/auth/phone/resolutions/" + secret + "/confirm");
        assertThat(match).isNotNull();
        assertThat(match.routeTemplate())
                .isEqualTo("/auth/phone/resolutions/:resolutionToken/confirm")
                .doesNotContain(secret);
    }

    private Response post(String path, String body, String bearer, String idempotencyKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                JsonParser.parseString(response.body()).getAsJsonObject());
    }

    private static String phoneBody(String phone) {
        return "{\"phone\":\"" + phone
                + "\",\"purpose\":\"login_or_bind\",\"continuation\":{\"intent\":\"none\"}}";
    }

    private static long count(String sql, long accountId) throws Exception {
        return ((Number) db.query(sql, statement -> statement.setLong(1, accountId))
                .getFirst().get("n")).longValue();
    }

    private static void ageAccount(long accountId, long timestamp) throws Exception {
        db.update("UPDATE \"t_account\" SET \"createTime\"=? WHERE \"id\"=?", statement -> {
            statement.setLong(1, timestamp);
            statement.setLong(2, accountId);
        });
        db.update("UPDATE \"t_account_profile\" SET \"createTime\"=? WHERE \"accountId\"=?", statement -> {
            statement.setLong(1, timestamp);
            statement.setLong(2, accountId);
        });
        db.update("UPDATE \"t_auth_session\" SET \"createdAt\"=? WHERE \"accountId\"=?", statement -> {
            statement.setLong(1, timestamp);
            statement.setLong(2, accountId);
        });
        db.update("UPDATE \"t_device_credential\" SET \"createdAt\"=?,\"updatedAt\"=? "
                + "WHERE \"accountId\"=?", statement -> {
            statement.setLong(1, timestamp);
            statement.setLong(2, timestamp);
            statement.setLong(3, accountId);
        });
    }

    private void sendCompleteRequestAndDisconnect(String path, String bearer, String idempotencyKey)
            throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String request = "POST " + HttpGateway.BASE_PATH + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + gateway.localPort() + "\r\n"
                + "Authorization: Bearer " + bearer + "\r\n"
                + "Idempotency-Key: " + idempotencyKey + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        try (Socket socket = new Socket("127.0.0.1", gateway.localPort())) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            socket.shutdownOutput();
        }
    }

    private static void awaitCount(String sql, long accountId, long expected) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        long actual;
        do {
            actual = count(sql, accountId);
            if (actual == expected) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        assertThat(actual).isEqualTo(expected);
    }

    private static void assertSuccess(Response response) {
        assertThat(response.status).isEqualTo(200);
        assertThat(response.contentType).startsWith("application/json");
        assertThat(response.json.get("code").getAsInt()).isZero();
        assertThat(response.json.has("data")).isTrue();
        assertThat(response.json.has("msg")).isFalse();
        assertThat(response.json.has("detail")).isFalse();
    }

    private static void assertError(Response response, int status, int code, String detail) {
        assertThat(response.status).isEqualTo(status);
        assertThat(response.contentType).startsWith("application/json");
        assertThat(response.json.get("code").getAsInt()).isEqualTo(code);
        assertThat(response.json.get("msg").getAsString()).isNotBlank();
        assertThat(response.json.get("detail").getAsString()).isEqualTo(detail);
        assertThat(response.json.has("data")).isFalse();
    }

    private record Response(int status, String contentType, JsonObject json) { }
}
