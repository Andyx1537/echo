package com.echo.infra.embedding;

import com.echo.infra.vector.InMemoryVectorStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MockEmbeddingClient} 单测：确定性、默认维度，且与向量库既有确定性哈希<b>逐位一致</b>
 * （保证默认走 mock 时行为不变、不破坏现有实现）。
 */
class MockEmbeddingClientTest {

    @Test
    void deterministicAndDefaultDim768() {
        MockEmbeddingClient mock = new MockEmbeddingClient();
        float[] a = mock.embed("怀旧 市井 温暖");
        float[] b = mock.embed("怀旧 市井 温暖");
        assertThat(mock.dimension()).isEqualTo(IEmbeddingClient.DEFAULT_DIM);
        assertThat(a).hasSize(IEmbeddingClient.DEFAULT_DIM).containsExactly(b);
    }

    @Test
    void emptyOrNullReturnsZeroVector() {
        MockEmbeddingClient mock = new MockEmbeddingClient(8);
        assertThat(mock.embed(null)).containsExactly(new float[8]);
        assertThat(mock.embed("")).containsExactly(new float[8]);
    }

    @Test
    void matchesLegacyDefaultEncodeBitForBit() {
        // 默认 InMemoryVectorStore（mock 嵌入）的 encode 应与之前的接口默认哈希逐位相同
        InMemoryVectorStore store = new InMemoryVectorStore();
        float[] viaStore = store.encode("换了个方式一直陪着你");
        float[] viaMock = new MockEmbeddingClient().embed("换了个方式一直陪着你");
        assertThat(viaStore).containsExactly(viaMock);
    }

    @Test
    void vectorStoreUsesInjectedEmbeddingClient() {
        // 注入自定义嵌入通道后，encode 委托给它
        IEmbeddingClient fixed = text -> {
            float[] vector = new float[IEmbeddingClient.DEFAULT_DIM];
            java.util.Arrays.fill(vector, 9f);
            return vector;
        };
        InMemoryVectorStore store = new InMemoryVectorStore(fixed);
        assertThat(store.encode("任意")).hasSize(IEmbeddingClient.DEFAULT_DIM).containsOnly(9f);
    }

    @Test
    void vectorStoreRejectsWrongDimension() {
        InMemoryVectorStore store = new InMemoryVectorStore(text -> new float[3]);
        assertThatThrownBy(() -> store.encode("任意"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected=768, actual=3");
    }
}
