package com.echo.http.exposure;

import com.echo.infra.persistence.PgDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 曝光 reqId 落库后，换 Registry 实例仍能反查同一份快照。门控 {@code ECHO_TEST_PG_URL}。
 */
class FeedRequestRegistryPgTest {
    private static PgDb db;

    @BeforeAll
    static void connect() {
        String url = System.getenv("ECHO_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "设置 ECHO_TEST_PG_URL 后运行曝光快照 PG 测试");
        Properties p = new Properties();
        p.setProperty("db.name", "echo");
        p.setProperty("jdbcUrl", url);
        p.setProperty("driverClassName", "org.postgresql.Driver");
        p.setProperty("username", System.getenv().getOrDefault("ECHO_TEST_PG_USER", "echo"));
        String password = System.getenv("ECHO_TEST_PG_PASSWORD");
        if (password != null) {
            p.setProperty("password", password);
        }
        p.setProperty("maximumPoolSize", "4");
        db = new PgDb(p);
        try {
            db.query("SELECT 1 FROM \"t_feed_request\" LIMIT 1", null);
        } catch (SQLException e) {
            throw new AssertionError("测试库缺少 t_feed_request，需要 schema 2026091406+", e);
        }
    }

    @AfterAll
    static void close() {
        if (db != null) {
            db.shutdown();
        }
    }

    @BeforeEach
    void clean() throws SQLException {
        assumeTrue(db != null);
        db.update("DELETE FROM \"t_feed_request\"");
    }

    @Test
    void otherRegistryInstanceLooksUpTheSameReqId() {
        ExposureConfig config = ExposureConfig.forTest();
        FeedRequestRegistry first = new FeedRequestRegistry(config, db);
        String reqId = first.register(42L, FeedRequestRegistry.KIND_CARD,
                FeedRequestRegistry.SURFACE_IMMERSIVE, List.of("1001", "1002"),
                Set.of("1001"), "recent", "warm");

        FeedRequestRegistry.Snapshot fromOther = new FeedRequestRegistry(config, db).lookup(reqId);
        assertThat(fromOther).isNotNull();
        assertThat(fromOther.viewerId()).isEqualTo(42L);
        assertThat(fromOther.targetKind()).isEqualTo(FeedRequestRegistry.KIND_CARD);
        assertThat(fromOther.surface()).isEqualTo(FeedRequestRegistry.SURFACE_IMMERSIVE);
        assertThat(fromOther.contains("1001")).isTrue();
        assertThat(fromOther.contains("1002")).isTrue();
        assertThat(fromOther.contains("9999")).isFalse();
        assertThat(fromOther.boostIds()).contains("1001");
        assertThat(fromOther.channel()).isEqualTo("recent");
        assertThat(fromOther.pool()).isEqualTo("warm");
        assertThat(fromOther.deliveredPositions().get("1002")).isEqualTo(1);

        ExposureRecorder recorder = new ExposureRecorder(config,
                new FeedRequestRegistry(config, db), null, new com.aengine.util.id.IDGenerator(7L));
        ExposureRecorder.Outcome out = recorder.record(reqId, 42L, List.of(
                new ExposureRecorder.Item("1001", 0, 3000L, fromOther.deliveredAt() + 2_000L)));
        assertThat(out.accepted()).isEqualTo(1);
        assertThat(out.rejected()).isZero();
    }
}
