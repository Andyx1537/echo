package com.echo.infra.vector;

import com.echo.infra.embedding.EmbeddingDescriptor;
import java.util.List;

/**
 * 向量存储/检索抽象（对应 TECH-P1 §4.1/§4.3 / BE-4）。
 *
 * <p>承载个人向量(Self Vector)的编码、写入、读取与相似度检索，用于意识空间生成与共鸣匹配。
 * 抽象成接口便于切换向量库实现（内存 mock ↔ pgvector），便于单测。</p>
 *
 * <p>实现：</p>
 * <ul>
 *   <li>{@link InMemoryVectorStore}：默认/DB 关闭态，纯内存，供联调与单测；</li>
 *   <li>{@link PgVectorStore}：DB 开启态，基于 {@code PgDb} + pgvector（BE-4）。</li>
 * </ul>
 */
public interface IVectorStore {

    /**
     * 向量维度常量：与 {@code schema.sql} 的 {@code t_self_vector.embedding vector(768)} 一致。
     * 集中在此一处定义，编码/建表/检索统一引用。
     */
    int DIM = 768;

    default EmbeddingDescriptor descriptor() {
        return new EmbeddingDescriptor("internal", "legacy", "v1", DIM);
    }

    static void requireDimension(float[] vector) {
        if (vector == null || vector.length != DIM) {
            throw new IllegalArgumentException("vector dimension mismatch: expected=" + DIM
                    + ", actual=" + (vector == null ? "null" : vector.length));
        }
    }

    /**
     * 将补全后的偏好文本编码为个人向量（占位实现：确定性哈希）。
     *
     * <p>占位编码：把字符按位散列到 {@link #DIM} 维上，<b>确定性</b>（同输入同输出），
     * 后续由真实嵌入模型替换。作为接口默认方法，保证各实现行为一致。</p>
     *
     * @param enrichedPrefs 补全后的偏好（JSON 文本占位）
     * @return {@link #DIM} 维向量
     */
    default float[] encode(String enrichedPrefs) {
        float[] v = new float[DIM];
        if (enrichedPrefs == null || enrichedPrefs.isEmpty()) {
            return v;
        }
        for (int i = 0; i < enrichedPrefs.length(); i++) {
            v[i % DIM] += enrichedPrefs.charAt(i);
        }
        return v;
    }

    /**
     * 写入/更新某账号的个人向量（幂等 upsert）。
     *
     * @param accountId 账号 ID
     * @param vector    个人向量（长度应为 {@link #DIM}）
     */
    void upsert(long accountId, float[] vector);

    /**
     * 读取某账号的个人向量（共鸣检索时取"我的向量"用）。
     *
     * @param accountId 账号 ID
     * @return 向量；不存在时返回 {@code null}
     */
    float[] get(long accountId);

    /**
     * 检索与给定向量最相似的 Top-K 账号（共鸣候选）。
     *
     * <p>按余弦距离升序（越相似越靠前），过滤掉距离超过 {@code threshold} 者，最多取 {@code k} 个。
     * 返回的 {@link ScoredId#score()} 为余弦距离（越小越相似）。</p>
     *
     * @param query     查询向量
     * @param k         返回数量上限
     * @param threshold 余弦距离阈值上限（命中需 {@code score <= threshold}）
     * @return 命中项（按距离升序）
     */
    List<ScoredId> topN(float[] query, int k, double threshold);
}
