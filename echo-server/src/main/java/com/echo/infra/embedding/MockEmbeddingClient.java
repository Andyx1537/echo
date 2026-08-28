package com.echo.infra.embedding;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link IEmbeddingClient} 的假实现：返回<b>确定性伪向量</b>（同输入同输出），不发起任何网络调用。
 *
 * <p>编码算法与 {@link com.echo.infra.vector.IVectorStore#encode(String)} 的占位实现<b>完全一致</b>
 * （把字符按位散列到各维），因此把它作为向量库的默认嵌入通道时，<b>行为与既有实现逐位相同</b>，
 * 不破坏现有联调/单测（符合 Aengine「外部服务一律 mock」约定）。</p>
 */
@Slf4j
public class MockEmbeddingClient implements IEmbeddingClient {

    private final int dim;

    /** 默认 768 维，与 {@code IVectorStore.DIM} 对齐。 */
    public MockEmbeddingClient() {
        this(DEFAULT_DIM);
    }

    /** 显式指定维度（便于单测）。 */
    public MockEmbeddingClient(int dim) {
        this.dim = dim > 0 ? dim : DEFAULT_DIM;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dim];
        if (text == null || text.isEmpty()) {
            return v;
        }
        for (int i = 0; i < text.length(); i++) {
            v[i % dim] += text.charAt(i);
        }
        log.debug("MockEmbeddingClient.embed 确定性伪向量, dim={}, textLen={}", dim, text.length());
        return v;
    }

    @Override
    public int dimension() {
        return dim;
    }
}
