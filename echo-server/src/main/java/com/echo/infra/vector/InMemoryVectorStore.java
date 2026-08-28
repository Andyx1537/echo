package com.echo.infra.vector;

import com.echo.infra.embedding.IEmbeddingClient;
import com.echo.infra.embedding.MockEmbeddingClient;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link IVectorStore} 的内存假实现：默认/DB 关闭态下使用，供 P1 主流程联调与单测。
 *
 * <p>编码走可插拔的 {@link IEmbeddingClient}（<b>默认 {@link MockEmbeddingClient}</b>，其算法与接口
 * 默认哈希逐位一致，故默认行为不变）；检索用余弦距离在内存集合上做 Top-K，行为与
 * {@link PgVectorStore} 的 pgvector 检索语义对齐（score=余弦距离，越小越相似）。不持久化、无并发优化。</p>
 */
@Slf4j
public class InMemoryVectorStore implements IVectorStore {

    private final Map<Long, float[]> store = new ConcurrentHashMap<>();

    /** 嵌入通道：默认 mock（确定性伪向量），接真实供应商时由工厂注入 {@code ApiEmbeddingClient}。 */
    private final IEmbeddingClient embeddingClient;

    /** 默认：mock 嵌入（无 key 可联调，行为与既有确定性哈希一致）。 */
    public InMemoryVectorStore() {
        this(new MockEmbeddingClient());
    }

    /** 注入自定义嵌入通道（拿到百炼 key 后经 {@code EmbeddingClientFactory} 切真）。 */
    public InMemoryVectorStore(IEmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient != null ? embeddingClient : new MockEmbeddingClient();
    }

    @Override
    public float[] encode(String enrichedPrefs) {
        return embeddingClient.embed(enrichedPrefs);
    }

    @Override
    public void upsert(long accountId, float[] vector) {
        store.put(accountId, vector);
        log.debug("InMemoryVectorStore.upsert accountId={}, size={}", accountId, store.size());
    }

    @Override
    public float[] get(long accountId) {
        return store.get(accountId);
    }

    @Override
    public List<ScoredId> topN(float[] query, int k, double threshold) {
        List<ScoredId> result = new ArrayList<>();
        store.entrySet().stream()
                .map(e -> new ScoredId(e.getKey(), cosineDistance(query, e.getValue())))
                .filter(s -> s.score() <= threshold)
                .sorted(Comparator.comparingDouble(ScoredId::score))
                .limit(Math.max(0, k))
                .forEach(result::add);
        return result;
    }

    /** 余弦距离 = 1 - 余弦相似度，范围约 [0, 2]，与 pgvector {@code <=>} 一致。 */
    private static double cosineDistance(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 1.0; // 任一为零向量：视为不相关（距离 1）
        }
        return 1.0 - dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
