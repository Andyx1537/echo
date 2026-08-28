package com.echo.infra.vector;

/**
 * 向量检索命中项：账号 ID + 相似度得分（TECH-P1 §4.3）。
 *
 * <p>{@code score} 为 pgvector 余弦距离算子 {@code <=>} 的结果（同时也是
 * {@link IVectorStore} 内存实现里 {@code 1 - cosine} 的结果）：<b>越小越相似</b>，
 * 取值范围约 [0, 2]。检索按 score 升序排序，并过滤掉 score 超过阈值者。</p>
 */
public record ScoredId(long accountId, double score) {
}
