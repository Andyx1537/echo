package com.echo.http.work;

import com.echo.infra.persistence.PgDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 匿名广场批次落库后，换 Store 实例仍是同一批。门控 {@code ECHO_TEST_PG_URL}。
 */
class AnonPlazaBatchStorePgTest {
    private static PgDb db;

    @BeforeAll
    static void connect() {
        String url = System.getenv("ECHO_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "设置 ECHO_TEST_PG_URL 后运行匿名批次 PG 测试");
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
            db.query("SELECT 1 FROM \"t_anon_plaza_batch\" LIMIT 1", null);
        } catch (SQLException e) {
            throw new AssertionError("测试库缺少 t_anon_plaza_batch，需要 schema 2026091405+", e);
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
        db.update("DELETE FROM \"t_anon_plaza_batch\"");
    }

    @Test
    void newStoreInstanceReplaysTheSameBatchUntilTtl() {
        List<Work> visible = works(1, 35);
        List<Work> reversed = new ArrayList<>(visible);
        Collections.reverse(reversed);
        long viewer = 9001L;
        long t0 = 2_000L;

        List<Work> first = new AnonPlazaBatchStore(db).freeze(viewer, visible, t0);
        List<Work> held = new AnonPlazaBatchStore(db).freeze(viewer, reversed, t0 + 1);
        assertThat(idsOf(held)).isEqualTo(idsOf(first));
        assertThat(first).hasSize(30);

        List<Work> renewed = new AnonPlazaBatchStore(db).freeze(
                viewer, reversed, t0 + AnonPlazaBatchStore.TTL_MS);
        assertThat(idsOf(renewed)).isEqualTo(idsOf(reversed.subList(0, 30)));
        assertThat(renewed.get(0).id).isNotEqualTo(first.get(0).id);
    }

    private static List<Long> idsOf(List<Work> works) {
        return works.stream().map(work -> work.id).toList();
    }

    private static List<Work> works(long seed, int n) {
        List<Work> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Work w = new Work();
            w.id = seed + i;
            w.authorId = 1;
            w.mediaType = Work.MediaType.IMAGE;
            w.mediaKey = "m";
            w.title = "w-" + i;
            w.body = w.title;
            w.status = Work.Status.PUBLIC;
            w.visibility = "public";
            w.publishedAt = 10L + i;
            w.createdAt = w.publishedAt;
            w.updatedAt = w.publishedAt;
            out.add(w);
        }
        return out;
    }
}
