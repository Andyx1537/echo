package com.echo.http.auth;

import com.aengine.util.id.IDGenerator;
import com.echo.http.ApiException;
import com.echo.http.RequestContext;
import com.echo.http.Router;
import com.echo.infra.persistence.PgDb;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** PH-01/10/12/15/16/18/19/21-24 against the real persistent identity boundary. */
class PgAuthServiceTest {
    private static PgDb db;
    private MutableClock clock;
    private StubSmsProvider sms;
    private PgAuthService auth;

    @BeforeAll
    static void connect() {
        String url = System.getenv("ECHO_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "设置 ECHO_TEST_PG_URL 后运行真实 Auth PG 测试");
        Properties p = new Properties();
        p.setProperty("db.name", "echo");
        p.setProperty("jdbcUrl", url);
        p.setProperty("driverClassName", "org.postgresql.Driver");
        p.setProperty("username", System.getenv().getOrDefault("ECHO_TEST_PG_USER", "echo"));
        String password = System.getenv("ECHO_TEST_PG_PASSWORD");
        if (password != null) p.setProperty("password", password);
        p.setProperty("maximumPoolSize", "6");
        db = new PgDb(p);
        try {
            db.query("SELECT 1 FROM \"t_auth_session\" LIMIT 1", null);
        } catch (SQLException e) {
            throw new AssertionError("测试库缺少 2026090701 Auth schema", e);
        }
    }

    @AfterAll
    static void close() { if (db != null) db.shutdown(); }

    @BeforeEach
    void reset() throws SQLException {
        assumeTrue(db != null);
        for (String table : new String[]{"t_auth_idempotency", "t_auth_rate_event", "t_auth_audit",
                "t_phone_resolution", "t_phone_challenge", "t_phone_credential", "t_auth_session",
                "t_device_credential", "t_account_profile", "t_account"}) {
            db.update("DELETE FROM \"" + table + "\"");
        }
        clock = new MutableClock(Instant.parse("2026-09-07T00:00:00Z"));
        sms = new StubSmsProvider();
        auth = new PgAuthService(db, new IDGenerator(57), "test-auth-secret-that-is-longer-than-32-characters",
                sms, ContinuationPolicy.noneOnly(), clock);
    }

    @Test
    void deviceIssueRestoreIdempotencyAndConflict() {
        Map<String, Object> issued = auth.deviceSession(null, nonce("a"), "device-key", "127.0.0.1");
        Map<String, Object> replay = auth.deviceSession(null, nonce("a"), "device-key", "127.0.0.1");
        assertThat(replay).isEqualTo(issued);
        assertThat(issued.get("deviceCredentialAction")).isEqualTo("issued");
        assertThat(auth.authenticate((String) issued.get("sessionToken"))).isNotNull();

        Map<String, Object> restored = auth.deviceSession((String) issued.get("deviceCredential"), null,
                "restore-key", "127.0.0.2");
        assertThat(restored.get("accountId")).isEqualTo(issued.get("accountId"));
        assertThat(restored.get("deviceCredentialAction")).isEqualTo("restored");

        assertThatThrownBy(() -> auth.deviceSession((String) issued.get("deviceCredential"), nonce("b"),
                "restore-key", "127.0.0.2"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).detail())
                        .isEqualTo("device_session_idempotency_conflict"));
    }

    @Test
    void challengeAndVerifyAreBoundToTheAnonymousSessionAndReplay() {
        Map<String, Object> device = auth.deviceSession(null, nonce("c"), "issue-c", "10.0.0.1");
        String token = (String) device.get("sessionToken");
        AuthPrincipal first = auth.authenticate(token);
        Map<String, Object> challenge = challenge(first, "+8613800000001", "challenge-c");
        assertThat(challenge(first, "+8613800000001", "challenge-c").get("challengeId"))
                .isEqualTo(challenge.get("challengeId"));

        AuthPrincipal second = auth.authenticate((String) auth.deviceSession(
                (String) device.get("deviceCredential"), null, "restore-c", "10.0.0.2").get("sessionToken"));
        assertThatThrownBy(() -> auth.verify(second, (String) challenge.get("challengeId"),
                sms.codeFor("+8613800000001"), "verify-wrong-session"))
                .isInstanceOf(ApiException.class);

        Map<String, Object> verified = auth.verify(first, (String) challenge.get("challengeId"),
                sms.codeFor("+8613800000001"), "verify-c");
        assertThat(verified.get("resolution")).isEqualTo("bind_current");
        Map<String, Object> verifyReplay = auth.verify(first, (String) challenge.get("challengeId"),
                sms.codeFor("+8613800000001"), "verify-c");
        assertThat(verifyReplay.get("resolutionToken")).isEqualTo(verified.get("resolutionToken"));
        assertThat(verifyReplay.get("resolution")).isEqualTo(verified.get("resolution"));
    }

    @Test
    void bindCurrentRevokesAnonymousCredentialAndConfirmReplaysAfterSessionRevocation() {
        Map<String, Object> device = auth.deviceSession(null, nonce("d"), "issue-d", "10.1.0.1");
        String anonymousToken = (String) device.get("sessionToken");
        AuthPrincipal principal = auth.authenticate(anonymousToken);
        Map<String, Object> challenge = challenge(principal, "+8613800000002", "challenge-d");
        Map<String, Object> verified = auth.verify(principal, (String) challenge.get("challengeId"),
                sms.codeFor("+8613800000002"), "verify-d");

        Map<String, Object> confirmed = auth.confirm(anonymousToken, (String) verified.get("resolutionToken"), "confirm-d");
        assertThat(confirmed.get("accountId")).isEqualTo(device.get("accountId"));
        assertThat(confirmed.get("previousAnonymousCredentialDisposition")).isEqualTo("revoked");
        assertThat(auth.authenticate(anonymousToken)).isNull();
        assertDetail(() -> auth.deviceSession(null, nonce("d"), "issue-d", "10.1.0.1"),
                "device_session_idempotency_conflict");
        assertThat(count("SELECT COUNT(*) AS n FROM \"t_auth_idempotency\" WHERE \"operation\"='device_session' "
                + "AND \"idempotencyKey\"='issue-d' AND \"status\"='revoked' AND \"responseCipher\" IS NULL"))
                .isEqualTo(1);
        Map<String, Object> confirmReplay = auth.confirm(anonymousToken,
                (String) verified.get("resolutionToken"), "confirm-d");
        assertThat(confirmReplay.get("sessionToken")).isEqualTo(confirmed.get("sessionToken"));
        assertThat(confirmReplay).containsEntry("deviceCredential", null).containsEntry("anonymousRecovery", null);

        Map<String, Object> rotated = auth.deviceSession((String) device.get("deviceCredential"), null,
                "rotate-d", "10.1.0.2");
        assertThat(rotated.get("deviceCredentialAction")).isEqualTo("rotated_after_bind");
        assertThat(rotated.get("accountId")).isNotEqualTo(device.get("accountId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void switchExistingOnlyRevokesCurrentSessionAndCreatesRecoveryCredential() {
        String phone = "+8613800000003";
        Map<String, Object> target = bindNewPhone(phone, "target");

        Map<String, Object> source = auth.deviceSession(null, nonce("source"), "issue-source", "10.2.0.1");
        String sourceToken = (String) source.get("sessionToken");
        AuthPrincipal sourcePrincipal = auth.authenticate(sourceToken);
        Map<String, Object> challenge = challenge(sourcePrincipal, phone, "challenge-source");
        Map<String, Object> verified = auth.verify(sourcePrincipal, (String) challenge.get("challengeId"),
                sms.codeFor(phone), "verify-source");
        assertThat(verified.get("resolution")).isEqualTo("switch_existing");

        Map<String, Object> confirmed = auth.confirm(sourceToken, (String) verified.get("resolutionToken"),
                "confirm-source");
        assertThat(confirmed.get("accountId")).isEqualTo(target.get("accountId"));
        assertThat(confirmed.get("previousAnonymousCredentialDisposition")).isEqualTo("retained_as_recovery");
        assertThat(auth.authenticate(sourceToken)).isNull();
        assertDetail(() -> auth.deviceSession(null, nonce("source"), "issue-source", "10.2.0.1"),
                "device_session_idempotency_conflict");
        Map<String, Object> recovery = (Map<String, Object>) confirmed.get("anonymousRecovery");
        assertThatThrownBy(() -> auth.deviceSession((String) recovery.get("recoveryCredential"), null,
                "recover-via-generic", "10.2.0.2"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).detail()).isEqualTo("device_credential_recovery_required"));

        String recoveryCredential = (String) recovery.get("recoveryCredential");
        Map<String, Object> recovered = auth.recoverAnonymousSession(recoveryCredential, "recover-source", "10.2.0.3");
        assertThat(recovered.get("accountId")).isEqualTo(source.get("accountId"));
        assertThat(recovered.get("phoneBound")).isEqualTo(false);
        assertThat(recovered.get("deviceCredentialAction")).isEqualTo("recovered");
        assertThat(recovered.get("deviceCredential")).isNotEqualTo(recoveryCredential);
        AuthPrincipal woken = auth.authenticate((String) recovered.get("sessionToken"));
        assertThat(woken.accountId()).isEqualTo(Long.parseLong((String) source.get("accountId")));
        assertThat(woken.anonymous()).isTrue();
        assertThatThrownBy(() -> auth.recoverAnonymousSession(recoveryCredential, "recover-source-again", "10.2.0.4"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).detail()).isEqualTo("device_credential_recovery_required"));
        Map<String, Object> replay = auth.recoverAnonymousSession(recoveryCredential, "recover-source", "10.2.0.3");
        assertThat(replay.get("sessionToken")).isEqualTo(recovered.get("sessionToken"));
    }

    @Test
    void rejectsInvalidE164AndInvalidContinuation() {
        SessionFixture fixture = anonymous("validation", "192.0.2.1");
        assertDetail(() -> auth.createChallenge(fixture.principal, "13800000000", "login_or_bind",
                "none", null, null, "bad-phone", "192.0.2.1"), "phone_invalid");
        assertDetail(() -> auth.createChallenge(fixture.principal, "+8613800000010", "login_or_bind",
                "private_onboarding_generation", "not-owned", "v1", "bad-continuation", "192.0.2.1"),
                "continuation_invalid");
        assertDetail(() -> auth.createChallenge(fixture.principal, "+8613800000010", "login_or_bind",
                "none", "unexpected-resource", null, "none-with-resource", "192.0.2.1"),
                "continuation_invalid");
        assertDetail(() -> auth.createChallenge(fixture.principal, "+8613800000010", "login_or_bind",
                "none", null, "v1", "none-with-schema", "192.0.2.1"),
                "continuation_invalid");
    }

    @Test
    void authApiRejectsFreeFormContinuationFields() {
        SessionFixture fixture = anonymous("continuation-fields", "192.0.2.10");
        Router router = new Router();
        new AuthApi(auth).register(router);
        Router.Match match = router.match("POST", "/auth/phone/challenges");
        JsonObject body = JsonParser.parseString("""
                {"phone":"+8613800000099","purpose":"login_or_bind","continuation":
                {"intent":"none","returnTo":"https://example.invalid/callback"}}
                """).getAsJsonObject();
        RequestContext request = new RequestContext("POST", match.pathParams, Map.of(), body,
                fixture.principal, Map.of("idempotency-key", "continuation-extra-field"), "192.0.2.10");
        assertDetail(() -> match.handle(request), "continuation_invalid");
    }

    @Test
    void challengeExpiresAtFiveMinutes() {
        SessionFixture fixture = anonymous("expires", "192.0.2.2");
        Map<String, Object> challenge = challenge(fixture.principal, "+8613800000011", "challenge-expire");
        clock.advanceMillis(5 * 60_000L);
        assertDetail(() -> auth.verify(fixture.principal, (String) challenge.get("challengeId"),
                sms.codeFor("+8613800000011"), "verify-expired"), "challenge_expired");
    }

    @Test
    void fifthWrongCodeLocksChallenge() {
        SessionFixture fixture = anonymous("wrong-code", "192.0.2.3");
        Map<String, Object> challenge = challenge(fixture.principal, "+8613800000012", "challenge-wrong");
        String id = (String) challenge.get("challengeId");
        for (int attempt = 1; attempt <= 4; attempt++) {
            int n = attempt;
            assertDetail(() -> auth.verify(fixture.principal, id, "000000", "wrong-" + n), "code_invalid");
        }
        assertDetail(() -> auth.verify(fixture.principal, id, "000000", "wrong-5"), "challenge_locked");
        assertDetail(() -> auth.verify(fixture.principal, id, sms.codeFor("+8613800000012"), "after-lock"),
                "challenge_locked");
    }

    @Test
    void wrongCodeReplayWithSameKeyConsumesOnlyOneAttempt() {
        SessionFixture fixture = anonymous("wrong-code-idempotency", "192.0.2.30");
        Map<String, Object> challenge = challenge(fixture.principal, "+8613800000030",
                "challenge-wrong-idempotency");
        String challengeId = (String) challenge.get("challengeId");

        assertCodeInvalidRemaining(() -> auth.verify(fixture.principal, challengeId, "000000",
                "wrong-idempotency-key"), 4);
        assertCodeInvalidRemaining(() -> auth.verify(fixture.principal, challengeId, "000000",
                "wrong-idempotency-key"), 4);
        assertThat(count("SELECT \"attemptCount\" AS n FROM \"t_phone_challenge\" WHERE \"challengeId\"='"
                + challengeId + "'")).isEqualTo(1);

        assertDetail(() -> auth.verify(fixture.principal, challengeId, "111111",
                "wrong-idempotency-key"), "idempotency_conflict");
        assertThat(count("SELECT \"attemptCount\" AS n FROM \"t_phone_challenge\" WHERE \"challengeId\"='"
                + challengeId + "'")).isEqualTo(1);
    }

    @Test
    void resendCooldownSupersedesOldOnlyAfterProviderSuccess() {
        SessionFixture fixture = anonymous("resend", "192.0.2.4");
        String phone = "+8613800000013";
        Map<String, Object> first = challenge(fixture.principal, phone, "resend-1");
        assertDetail(() -> challenge(fixture.principal, phone, "resend-too-soon"), "resend_cooldown");

        clock.advanceMillis(60_000L);
        sms.setAvailable(false);
        assertDetail(() -> challenge(fixture.principal, phone, "resend-provider-fail"), "sms_provider_unavailable");
        sms.setAvailable(true);
        Map<String, Object> firstVerified = auth.verify(fixture.principal, (String) first.get("challengeId"),
                sms.codeFor(phone), "verify-old-still-valid");
        assertThat(firstVerified.get("resolution")).isEqualTo("bind_current");

        SessionFixture other = anonymous("resend-other", "192.0.2.44");
        Map<String, Object> old = challenge(other.principal, "+8613800000014", "supersede-1");
        String oldCode = sms.codeFor("+8613800000014");
        clock.advanceMillis(60_000L);
        Map<String, Object> newer = challenge(other.principal, "+8613800000014", "supersede-2");
        assertThat(newer.get("challengeId")).isNotEqualTo(old.get("challengeId"));
        assertDetail(() -> auth.verify(other.principal, (String) old.get("challengeId"), oldCode,
                "verify-superseded"), "challenge_expired");
    }

    @Test
    void phoneAndDeviceSlidingWindowLimitsRejectNextRequest() {
        SessionFixture phoneFixture = anonymous("rate-phone", "198.51.100.1");
        for (int i = 0; i < 5; i++) {
            challenge(phoneFixture.principal, "+8613800000015", "phone-rate-" + i);
            clock.advanceMillis(60_000L);
        }
        assertDetail(() -> challenge(phoneFixture.principal, "+8613800000015", "phone-rate-block"),
                "rate_limited");

        resetRateEvents();
        SessionFixture deviceFixture = anonymous("rate-device", "198.51.100.2");
        for (int i = 0; i < 10; i++) {
            challenge(deviceFixture.principal, "+8613900000" + String.format("%03d", i), "device-rate-" + i);
            clock.advanceMillis(60_000L);
        }
        assertDetail(() -> challenge(deviceFixture.principal, "+8613900000999", "device-rate-block"),
                "rate_limited");

        resetRateEvents();
        String sharedIp = "198.51.100.200";
        for (int i = 0; i < 20; i++) {
            SessionFixture fixture = anonymous("rate-ip-" + i, "198.51.101." + (i + 1));
            challenge(fixture.principal, String.format("+861500000%04d", i), "ip-rate-" + i, sharedIp);
        }
        SessionFixture blocked = anonymous("rate-ip-blocked", "198.51.102.1");
        assertDetail(() -> challenge(blocked.principal, "+8615000000999", "ip-rate-blocked", sharedIp),
                "rate_limited");
    }

    @Test
    void resolutionExpiresRejectsWrongSessionAndDifferentKeyReplay() {
        SessionFixture expired = anonymous("resolution-expired", "203.0.113.1");
        Map<String, Object> challenge = challenge(expired.principal, "+8613800000016", "resolution-expired-c");
        Map<String, Object> resolution = auth.verify(expired.principal, (String) challenge.get("challengeId"),
                sms.codeFor("+8613800000016"), "resolution-expired-v");
        clock.advanceMillis(10 * 60_000L);
        assertDetail(() -> auth.confirm(expired.token, (String) resolution.get("resolutionToken"),
                "resolution-expired-confirm"), "resolution_expired");

        resetRateEvents();
        SessionFixture source = anonymous("wrong-session", "203.0.113.2");
        Map<String, Object> secondSession = auth.deviceSession(source.deviceCredential, null,
                "wrong-session-restore", "203.0.113.3");
        Map<String, Object> secondChallenge = challenge(source.principal, "+8613800000017", "wrong-session-c");
        Map<String, Object> secondResolution = auth.verify(source.principal,
                (String) secondChallenge.get("challengeId"), sms.codeFor("+8613800000017"), "wrong-session-v");
        assertDetail(() -> auth.confirm((String) secondSession.get("sessionToken"),
                (String) secondResolution.get("resolutionToken"), "wrong-session-confirm"),
                "resolution_session_mismatch");
        auth.confirm(source.token, (String) secondResolution.get("resolutionToken"), "right-confirm");
        assertDetail(() -> auth.confirm(source.token, (String) secondResolution.get("resolutionToken"),
                "different-key-confirm"), "resolution_used");
    }

    @Test
    void phoneOwnershipChangeBetweenVerifyAndConfirmDoesNotChangeBranch() {
        SessionFixture first = anonymous("race-first", "203.0.113.10");
        SessionFixture second = anonymous("race-second", "203.0.113.11");
        String phone = "+8613800000018";
        Map<String, Object> firstChallenge = challenge(first.principal, phone, "race-first-c");
        Map<String, Object> firstResolution = auth.verify(first.principal,
                (String) firstChallenge.get("challengeId"), sms.codeFor(phone), "race-first-v");
        Map<String, Object> secondChallenge = challenge(second.principal, phone, "race-second-c");
        Map<String, Object> secondResolution = auth.verify(second.principal,
                (String) secondChallenge.get("challengeId"), sms.codeFor(phone), "race-second-v");
        auth.confirm(second.token, (String) secondResolution.get("resolutionToken"), "race-second-confirm");
        assertDetail(() -> auth.confirm(first.token, (String) firstResolution.get("resolutionToken"),
                "race-first-confirm"), "phone_ownership_changed");
        assertThat(auth.authenticate(first.token)).isNotNull();
    }

    @Test
    void secretIdempotencyResponsesStopReplayingWithoutExpiringIssuedSessions() {
        SessionFixture device = anonymous("replay-window-device", "203.0.113.20");
        clock.advanceMillis(10 * 60_000L);
        assertDetail(() -> auth.deviceSession(null, nonce("replay-window-device"),
                "issue-replay-window-device", "203.0.113.20"), "device_session_idempotency_conflict");
        assertThat(auth.authenticate(device.token)).isNotNull();

        resetRateEvents();
        SessionFixture challengeFixture = anonymous("replay-window-challenge", "203.0.113.21");
        challenge(challengeFixture.principal, "+8613800000019", "replay-window-challenge-key");
        clock.advanceMillis(5 * 60_000L);
        assertDetail(() -> challenge(challengeFixture.principal, "+8613800000019",
                "replay-window-challenge-key"), "challenge_expired");

        resetRateEvents();
        SessionFixture verifyFixture = anonymous("replay-window-verify", "203.0.113.22");
        Map<String, Object> challenge = challenge(verifyFixture.principal, "+8613800000020",
                "replay-window-verify-c");
        auth.verify(verifyFixture.principal, (String) challenge.get("challengeId"),
                sms.codeFor("+8613800000020"), "replay-window-verify-v");
        clock.advanceMillis(10 * 60_000L);
        assertDetail(() -> auth.verify(verifyFixture.principal, (String) challenge.get("challengeId"),
                sms.codeFor("+8613800000020"), "replay-window-verify-v"), "resolution_expired");

        resetRateEvents();
        SessionFixture confirmFixture = anonymous("replay-window-confirm", "203.0.113.23");
        Map<String, Object> confirmChallenge = challenge(confirmFixture.principal, "+8613800000021",
                "replay-window-confirm-c");
        Map<String, Object> resolution = auth.verify(confirmFixture.principal,
                (String) confirmChallenge.get("challengeId"), sms.codeFor("+8613800000021"),
                "replay-window-confirm-v");
        Map<String, Object> confirmed = auth.confirm(confirmFixture.token,
                (String) resolution.get("resolutionToken"), "replay-window-confirm-key");
        clock.advanceMillis(10 * 60_000L);
        assertDetail(() -> auth.confirm(confirmFixture.token, (String) resolution.get("resolutionToken"),
                "replay-window-confirm-key"), "resolution_used");
        assertThat(auth.authenticate((String) confirmed.get("sessionToken"))).isNotNull();
        assertThat(count("SELECT COUNT(*) AS n FROM \"t_auth_idempotency\" "
                + "WHERE \"status\"='expired' AND \"responseCipher\" IS NULL")).isEqualTo(4);
    }

    private Map<String, Object> bindNewPhone(String phone, String suffix) {
        Map<String, Object> device = auth.deviceSession(null, nonce(suffix), "issue-" + suffix, "10.3.0.1");
        String token = (String) device.get("sessionToken");
        AuthPrincipal principal = auth.authenticate(token);
        Map<String, Object> challenge = challenge(principal, phone, "challenge-" + suffix);
        Map<String, Object> verified = auth.verify(principal, (String) challenge.get("challengeId"),
                sms.codeFor(phone), "verify-" + suffix);
        return auth.confirm(token, (String) verified.get("resolutionToken"), "confirm-" + suffix);
    }

    private Map<String, Object> challenge(AuthPrincipal principal, String phone, String key) {
        return challenge(principal, phone, key, "10.9.0.1");
    }

    private Map<String, Object> challenge(AuthPrincipal principal, String phone, String key, String ip) {
        return auth.createChallenge(principal, phone, "login_or_bind", "none", null, null, key, ip);
    }

    private SessionFixture anonymous(String suffix, String ip) {
        Map<String, Object> device = auth.deviceSession(null, nonce(suffix), "issue-" + suffix, ip);
        String token = (String) device.get("sessionToken");
        return new SessionFixture(token, (String) device.get("deviceCredential"), auth.authenticate(token));
    }

    private void resetRateEvents() {
        try { db.update("DELETE FROM \"t_auth_rate_event\""); }
        catch (SQLException e) { throw new AssertionError(e); }
    }

    private long count(String sql) {
        try { return ((Number) db.query(sql, null).getFirst().get("n")).longValue(); }
        catch (SQLException e) { throw new AssertionError(e); }
    }

    private static void assertDetail(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String detail) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).detail()).isEqualTo(detail));
    }

    private static void assertCodeInvalidRemaining(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                                   int remainingAttempts) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.detail()).isEqualTo("code_invalid");
                    assertThat(((Number) api.data().get("remainingAttempts")).intValue())
                            .isEqualTo(remainingAttempts);
                });
    }

    private static String nonce(String suffix) {
        return "bootstrap-nonce-abcdefghijklmnopqrstuvwxyz-" + suffix;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
        void advanceMillis(long millis) { instant = instant.plusMillis(millis); }
    }

    private record SessionFixture(String token, String deviceCredential, AuthPrincipal principal) { }
}
