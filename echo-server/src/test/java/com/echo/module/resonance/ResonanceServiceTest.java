package com.echo.module.resonance;

import com.echo.infra.vector.IVectorStore;
import com.echo.infra.vector.ScoredId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 共鸣匹配服务单测（§4.3）：取我的向量 → topN → 过滤自己 → 回候选。
 *
 * <p>查询是<b>纯读</b>：不再落 {@code t_resonance_record}（该表零消费方，见
 * {@link ResonanceService} 类注释）。</p>
 */
@ExtendWith(MockitoExtension.class)
class ResonanceServiceTest {

    @Mock
    private IVectorStore vectorStore;

    private ResonanceService service;

    @BeforeEach
    void setUp() {
        service = new ResonanceService(vectorStore);
    }

    @Test
    void returnsEmptyWhenNoSelfVector() {
        when(vectorStore.get(100L)).thenReturn(null);

        List<ScoredId> result = service.queryResonance(100L, 5, 1.0);

        assertThat(result).isEmpty();
        verify(vectorStore, never()).topN(any(), anyInt(), anyDouble());
    }

    @Test
    void filtersSelfFromCandidates() {
        long me = 100L;
        float[] myVec = new float[IVectorStore.DIM];
        when(vectorStore.get(me)).thenReturn(myVec);
        // topN 返回含自己 + 两个 peer（含自己被过滤）
        when(vectorStore.topN(eq(myVec), anyInt(), anyDouble())).thenReturn(List.of(
                new ScoredId(me, 0.0),
                new ScoredId(200L, 0.1),
                new ScoredId(300L, 0.2)));

        List<ScoredId> result = service.queryResonance(me, 5, 1.0);

        assertThat(result).extracting(ScoredId::accountId).containsExactly(200L, 300L);
    }

    @Test
    void queryIsReadOnly() {
        long me = 100L;
        float[] myVec = new float[IVectorStore.DIM];
        when(vectorStore.get(me)).thenReturn(myVec);
        when(vectorStore.topN(eq(myVec), anyInt(), anyDouble()))
                .thenReturn(List.of(new ScoredId(200L, 0.1)));

        service.queryResonance(me, 5, 1.0);

        // 只读向量库，不产生任何写操作（t_resonance_record 已无写入路径）
        verify(vectorStore).get(me);
        verify(vectorStore).topN(eq(myVec), anyInt(), anyDouble());
        org.mockito.Mockito.verifyNoMoreInteractions(vectorStore);
    }

    @Test
    void usesDefaultsWhenParamsNonPositive() {
        long me = 101L;
        when(vectorStore.get(me)).thenReturn(new float[IVectorStore.DIM]);
        when(vectorStore.topN(any(), eq(ResonanceService.DEFAULT_TOP_N + 1),
                eq(ResonanceService.DEFAULT_THRESHOLD))).thenReturn(List.of());

        List<ScoredId> result = service.queryResonance(me, 0, 0);

        assertThat(result).isEmpty();
        // 校验用默认 topN+1 与默认阈值发起检索
        verify(vectorStore).topN(any(), eq(ResonanceService.DEFAULT_TOP_N + 1),
                eq(ResonanceService.DEFAULT_THRESHOLD));
    }
}
