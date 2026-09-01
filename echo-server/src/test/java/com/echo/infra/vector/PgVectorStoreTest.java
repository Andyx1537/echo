package com.echo.infra.vector;

import com.aengine.persistence.db.PreparedStatementBinder;
import com.echo.infra.persistence.PgDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PgVectorStore} 的 SQL 调用与结果映射单测（mock {@link PgDb}）。
 *
 * <p>不连真实 PG：验证 upsert/topN/get 生成的 SQL 形态、参数绑定与行映射逻辑。</p>
 */
class PgVectorStoreTest {

    private PgDb db;
    private PgVectorStore store;

    @BeforeEach
    void setUp() {
        db = mock(PgDb.class);
        store = new PgVectorStore(db);
    }

    @Test
    void upsertUpdatesEmbeddingColumnByAccountId() throws Exception {
        when(db.update(anyString(), any())).thenReturn(1);

        store.upsert(42L, vector(1.0f, 2.0f, 3.0f));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<PreparedStatementBinder> binderCaptor = ArgumentCaptor.forClass(PreparedStatementBinder.class);
        verify(db).update(sqlCaptor.capture(), binderCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("UPDATE \"t_self_vector\"")
                .contains("\"embedding\" = ?::vector")
                .contains("WHERE \"accountId\" = ?");

        PreparedStatement ps = mock(PreparedStatement.class);
        binderCaptor.getValue().bind(ps);
        verify(ps).setString(1, PgVectorStore.toVectorLiteral(vector(1.0f, 2.0f, 3.0f)));
        verify(ps).setLong(2, 42L);
    }

    @Test
    void topNBuildsCosineDistanceQueryAndMapsRows() throws Exception {
        Map<String, Object> row1 = new HashMap<>();
        row1.put("accountId", 200L);
        row1.put("score", 0.1d);
        Map<String, Object> row2 = new HashMap<>();
        row2.put("accountId", 300L);
        row2.put("score", 0.4d);
        when(db.query(anyString(), any())).thenReturn(List.of(row1, row2));

        List<ScoredId> result = store.topN(vector(0.5f, 0.5f), 10, 0.8d);

        assertThat(result).containsExactly(new ScoredId(200L, 0.1d), new ScoredId(300L, 0.4d));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<PreparedStatementBinder> binderCaptor = ArgumentCaptor.forClass(PreparedStatementBinder.class);
        verify(db).query(sqlCaptor.capture(), binderCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("<=>").contains("ORDER BY score ASC").contains("LIMIT ?");

        PreparedStatement ps = mock(PreparedStatement.class);
        binderCaptor.getValue().bind(ps);
        // query 向量绑定两次（SELECT + WHERE），阈值、k 各一次
        String literal = PgVectorStore.toVectorLiteral(vector(0.5f, 0.5f));
        verify(ps).setString(1, literal);
        verify(ps).setString(2, "mock");
        verify(ps).setString(3, "deterministic-char-hash");
        verify(ps).setString(4, "v1");
        verify(ps).setString(5, literal);
        verify(ps).setDouble(6, 0.8d);
        verify(ps).setInt(7, 10);
    }

    @Test
    void getParsesVectorLiteral() throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("embedding", PgVectorStore.toVectorLiteral(vector(1.5f, 2.5f, 3.5f)));
        when(db.query(anyString(), any())).thenReturn(List.of(row));

        float[] v = store.get(42L);

        assertThat(v).hasSize(IVectorStore.DIM);
        assertThat(v[0]).isEqualTo(1.5f);
        assertThat(v[1]).isEqualTo(2.5f);
        assertThat(v[2]).isEqualTo(3.5f);
    }

    @Test
    void getReturnsNullWhenNoRow() throws Exception {
        when(db.query(anyString(), any())).thenReturn(List.of());
        assertThat(store.get(42L)).isNull();
    }

    @Test
    void vectorLiteralRoundTrip() {
        float[] v = {0.0f, -1.25f, 3.5f};
        assertThat(PgVectorStore.parseVectorLiteral(PgVectorStore.toVectorLiteral(v)))
                .containsExactly(v);
    }

    @Test
    void encodeIsDeterministicAndDim768() {
        float[] a = store.encode("怀旧 市井 温暖");
        float[] b = store.encode("怀旧 市井 温暖");
        assertThat(a).hasSize(IVectorStore.DIM).containsExactly(b);
    }

    private static float[] vector(float... prefix) {
        float[] vector = new float[IVectorStore.DIM];
        System.arraycopy(prefix, 0, vector, 0, prefix.length);
        return vector;
    }
}
