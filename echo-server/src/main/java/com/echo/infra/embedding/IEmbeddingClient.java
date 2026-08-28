package com.echo.infra.embedding;

/**
 * 文本向量嵌入客户端抽象（对齐 {@link com.echo.infra.llm.ILlmClient} 的插件化设计）。
 *
 * <p>把文本编码为固定维度的嵌入向量，供 {@link com.echo.infra.vector.IVectorStore} 写入/检索。
 * 抽象成接口便于切换供应商（百炼 text-embedding ↔ 本地/自建），便于单测（外部服务一律 mock）。</p>
 *
 * <p>默认维度与 {@code t_self_vector.embedding vector(768)} 及
 * {@link com.echo.infra.vector.IVectorStore#DIM} 一致。</p>
 */
public interface IEmbeddingClient {

    /** 默认嵌入维度，与向量库 {@code IVectorStore.DIM} 对齐。 */
    int DEFAULT_DIM = 768;

    /**
     * 把文本编码为嵌入向量。
     *
     * @param text 待编码文本（{@code null}/空串应返回零向量而非抛异常）
     * @return 长度为 {@link #dimension()} 的向量
     */
    float[] embed(String text);

    /** 嵌入向量维度。 */
    default int dimension() {
        return DEFAULT_DIM;
    }
}
