package com.echo.http.onboarding;

import com.aengine.util.id.IDGenerator;
import com.echo.http.store.InMemoryEchoStore;
import com.echo.infra.persistence.PgDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real PostgreSQL checks for restart persistence and atomic one-window confirmation. */
class PgOnboardingRepositoryTest {
    private static PgDb db;
    private static final long ACCOUNT_ID = 9_609_060_001L;
    private final IDGenerator ids = new IDGenerator(43);

    @BeforeAll
    static void connect() {
        String url = System.getenv("ECHO_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "设置 ECHO_TEST_PG_URL 后运行真实 Onboarding PG 测试");
        Properties properties = new Properties();
        properties.setProperty("db.name", "echo");
        properties.setProperty("jdbcUrl", url);
        properties.setProperty("driverClassName", "org.postgresql.Driver");
        properties.setProperty("username", System.getenv().getOrDefault("ECHO_TEST_PG_USER", "echo"));
        String password = System.getenv("ECHO_TEST_PG_PASSWORD");
        if (password != null) properties.setProperty("password", password);
        properties.setProperty("maximumPoolSize", "4");
        db = new PgDb(properties);
        try {
            db.query("SELECT 1 FROM \"t_onboarding_session\" LIMIT 1", null);
        } catch (SQLException e) {
            throw new AssertionError("测试库缺少 2026090601 Onboarding schema", e);
        }
    }

    @AfterAll
    static void close() {
        if (db != null) db.shutdown();
    }

    @BeforeEach
    void reset() throws SQLException {
        assumeTrue(db != null);
        db.update("DELETE FROM \"t_onboarding_session\" WHERE \"accountId\"=" + ACCOUNT_ID);
        db.update("DELETE FROM \"t_pet\" WHERE \"ownerAccountId\"=" + ACCOUNT_ID);
        db.update("DELETE FROM \"t_account_profile\" WHERE \"accountId\"=" + ACCOUNT_ID);
        db.update("INSERT INTO \"t_account_profile\"(\"accountId\",\"deviceId\",\"guest\") "
                + "VALUES(" + ACCOUNT_ID + ",'pg-onboarding-test',0)");
    }

    @Test
    void persistsAcrossRepositoryInstancesAndRollsBackWindowWithSession() {
        PgOnboardingRepository first = new PgOnboardingRepository(db);
        OnboardingAggregate session = readySession();
        first.create(session);

        PgOnboardingRepository afterRestart = new PgOnboardingRepository(db);
        assertThat(afterRestart.find(session.onboardingId).petName).isEqualTo("麦麦");

        EchoOnboardingWindowPort windows = new EchoOnboardingWindowPort(new InMemoryEchoStore(), ids);
        assertThatThrownBy(() -> afterRestart.mutate(session.onboardingId, ACCOUNT_ID, 0,
                "confirm-fail", "hash-fail", (aggregate, transaction) -> {
                    windows.confirm(aggregate, aggregate.candidates.getFirst(), transaction);
                    throw new SQLException("fault after window write");
                })).hasMessageContaining("建档进度暂时没能保存");
        assertThat(afterRestart.find(session.onboardingId).status).isEqualTo("ready_to_confirm");
        assertThat(count("SELECT COUNT(*) AS n FROM \"t_pet\" WHERE \"ownerAccountId\"=" + ACCOUNT_ID))
                .isZero();

        Map<String, Object> result = afterRestart.mutate(session.onboardingId, ACCOUNT_ID, 0,
                "confirm-ok", "hash-ok", (aggregate, transaction) -> {
                    OnboardingWindowPort.Result window = windows.confirm(
                            aggregate, aggregate.candidates.getFirst(), transaction);
                    aggregate.confirmedPetId = window.petId();
                    aggregate.confirmedWindowId = window.windowId();
                    aggregate.status = "confirmed";
                    aggregate.currentStep = "done";
                    return Map.of("petId", window.petId(), "windowId", window.windowId());
                });
        assertThat(result.get("petId")).isEqualTo(result.get("windowId"));
        assertThat(count("SELECT COUNT(*) AS n FROM \"t_pet\" WHERE \"ownerAccountId\"=" + ACCOUNT_ID))
                .isEqualTo(1L);
        assertThat(new PgOnboardingRepository(db).find(session.onboardingId).status).isEqualTo("confirmed");
    }

    private OnboardingAggregate readySession() {
        OnboardingAggregate session = new OnboardingAggregate();
        session.onboardingId = "pg-onboarding-session";
        session.accountId = ACCOUNT_ID;
        session.petName = "麦麦";
        session.flowVersion = "v1";
        session.questionnaireVersion = "v1";
        session.status = "ready_to_confirm";
        session.currentStep = "confirm";
        session.createRequestHash = "create-hash";
        session.createdAt = System.currentTimeMillis();
        session.updatedAt = session.createdAt;
        OnboardingAggregate.Subject subject = new OnboardingAggregate.Subject();
        subject.subjectId = "subject-1";
        subject.species = "狗";
        session.subjects.add(subject);
        session.selectedSubjectId = subject.subjectId;
        OnboardingAggregate.Candidate candidate = new OnboardingAggregate.Candidate();
        candidate.candidateId = "candidate-1";
        candidate.signature = "从熟悉的日常，慢慢认出它";
        candidate.gradient = "sunset";
        candidate.emoji = "🐾";
        session.candidates.add(candidate);
        session.selectedCandidateId = candidate.candidateId;
        session.consent.granted = true;
        session.consent.consentVersion = 1;
        return session;
    }

    private long count(String sql) {
        try {
            return ((Number) db.query(sql, null).getFirst().get("n")).longValue();
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }
}
