package com.echo.infra.vector;

import com.echo.infra.embedding.IEmbeddingClient;
import com.echo.infra.embedding.MockEmbeddingClient;
import com.echo.infra.persistence.PgDb;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link IVectorStore} 的 pgvector 真实现（BE-4）：基于 {@link PgDb} 读写
 * {@code t_self_vector.embedding vector(768)} 列，用余弦距离算子 {@code <=>} 做近邻检索。
 *
 * <p>列归属约定（与 {@code com.echo.module.mind.SelfVectorRepository} 分工）：</p>
 * <ul>
 *   <li>关系元数据列（id/accountId/dim/vectorRef/normHash）由 {@code SelfVectorRepository}
 *       的通用 CRUD 维护（含行的创建）；</li>
 *   <li>{@code embedding} 向量列<b>仅</b>由本类维护——它不在实体注解里，通用 CRUD 不读写它。</li>
 * </ul>
 *
 * <p>因此 {@link #upsert} 走 {@code UPDATE ... SET embedding WHERE accountId}（行由仓储先建好），
 * 而非 INSERT/ON CONFLICT 自管整行——避免与仓储对同一行的双写与主键协调（见交付报告说明）。</p>
 *
 * <p>{@code encode} 走可插拔的 {@link IEmbeddingClient}（<b>默认 {@link MockEmbeddingClient}</b>，其算法
 * 与接口默认哈希逐位一致，故默认行为与内存实现一致、不破坏现有行为）；接真实供应商时由工厂注入。</p>
 */
@Slf4j
public class PgVectorStore implements IVectorStore {

    private final PgDb db;

    /** 嵌入通道：默认 mock（确定性伪向量），接真实供应商时由工厂注入 {@code ApiEmbeddingClient}。 */
    private final IEmbeddingClient embeddingClient;

    /** 默认：mock 嵌入（行为与既有确定性哈希一致）。 */
    public PgVectorStore(PgDb db) {
        this(db, new MockEmbeddingClient());
    }

    /** 注入自定义嵌入通道（拿到百炼 key 后经 {@code EmbeddingClientFactory} 切真）。 */
    public PgVectorStore(PgDb db, IEmbeddingClient embeddingClient) {
        this.db = db;
        this.embeddingClient = embeddingClient != null ? embeddingClient : new MockEmbeddingClient();
    }

    @Override
    public float[] encode(String enrichedPrefs) {
        return embeddingClient.embed(enrichedPrefs);
    }

    @Override
    public void upsert(long accountId, float[] vector) {
        String sql = "UPDATE \"t_self_vector\" SET \"embedding\" = ?::vector WHERE \"accountId\" = ?";
        String literal = toVectorLiteral(vector);
        try {
            int n = db.update(sql, ps -> {
                ps.setString(1, literal);
                ps.setLong(2, accountId);
            });
            if (n == 0) {
                log.warn("PgVectorStore.upsert 未命中向量行(accountId={})；应先经 SelfVectorRepository 建行", accountId);
            }
        } catch (SQLException e) {
            throw new RuntimeException("pgvector upsert failed, accountId=" + accountId, e);
        }
    }

    @Override
    public float[] get(long accountId) {
        String sql = "SELECT \"embedding\"::text AS embedding FROM \"t_self_vector\" "
                + "WHERE \"accountId\" = ? AND \"embedding\" IS NOT NULL LIMIT 1";
        try {
            List<Map<String, Object>> rows = db.query(sql, ps -> ps.setLong(1, accountId));
            if (rows.isEmpty()) {
                return null;
            }
            Object v = rows.get(0).get("embedding");
            return v == null ? null : parseVectorLiteral(v.toString());
        } catch (SQLException e) {
            throw new RuntimeException("pgvector get failed, accountId=" + accountId, e);
        }
    }

    @Override
    public List<ScoredId> topN(float[] query, int k, double threshold) {
        // WHERE 不能引用 SELECT 别名，故 <=> 表达式在 SELECT 与 WHERE 各出现一次（query 绑定两次）。
        String sql = "SELECT \"accountId\" AS \"accountId\", (\"embedding\" <=> ?::vector) AS score "
                + "FROM \"t_self_vector\" "
                + "WHERE \"embedding\" IS NOT NULL AND (\"embedding\" <=> ?::vector) <= ? "
                + "ORDER BY score ASC LIMIT ?";
        String literal = toVectorLiteral(query);
        try {
            List<Map<String, Object>> rows = db.query(sql, ps -> {
                ps.setString(1, literal);
                ps.setString(2, literal);
                ps.setDouble(3, threshold);
                ps.setInt(4, Math.max(0, k));
            });
            List<ScoredId> result = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                long accountId = ((Number) row.get("accountId")).longValue();
                double score = ((Number) row.get("score")).doubleValue();
                result.add(new ScoredId(accountId, score));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("pgvector topN failed", e);
        }
    }

    /** float[] → pgvector 文本字面量，如 {@code [0.1,0.2,0.3]}。 */
    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /** pgvector 文本字面量 {@code [a,b,c]} → float[]。 */
    static float[] parseVectorLiteral(String literal) {
        String s = literal.trim();
        if (s.startsWith("[")) {
            s = s.substring(1);
        }
        if (s.endsWith("]")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isBlank()) {
            return new float[0];
        }
        String[] parts = s.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i].trim());
        }
        return v;
    }
}
