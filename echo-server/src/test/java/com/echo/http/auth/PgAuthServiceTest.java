package com.echo.http.auth;

import com.aengine.util.id.IDGenerator;
import com.echo.http.ApiException;
import com.echo.infra.persistence.PgDb;
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
        Map<String, Object> recovery = (Map<String, Object>) confirmed.get("anonymousRecovery");
        assertThatThrownBy(() -> auth.deviceSession((String) recovery.get("recoveryCredential"), null,
                "recover-via-generic", "10.2.0.2"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).detail()).isEqualTo("device_credential_recovery_required"));
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
        return auth.createChallenge(principal, phone, "login_or_bind", "none", null, null, key, "10.9.0.1");
    }

    private static String nonce(String suffix) {
        return ("bootstrap-nonce-abcdefghijklmnopqrstuvwxyz-" + suffix).substring(0, 32);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
