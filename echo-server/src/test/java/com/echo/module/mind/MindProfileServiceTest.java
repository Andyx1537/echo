package com.echo.module.mind;

import com.aengine.util.id.IDGenerator;
import com.echo.infra.llm.ILlmClient;
import com.echo.infra.vector.IVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 意识档案服务单测（§4.1）：偏好 → LLM 补全(含兜底) → 向量编码 → upsert → 落库。
 * 仓储/LLM/向量库一律 mock。
 */
@ExtendWith(MockitoExtension.class)
class MindProfileServiceTest {

    @Mock
    private MindProfileRepository mindProfileRepository;
    @Mock
    private SelfVectorRepository selfVectorRepository;
    @Mock
    private ILlmClient llmClient;
    @Mock
    private IVectorStore vectorStore;

    private MindProfileService service;

    @BeforeEach
    void setUp() {
        service = new MindProfileService(
                mindProfileRepository, selfVectorRepository, llmClient, vectorStore, new IDGenerator(1));
    }

    @Test
    void submitPrefsRejectsEmpty() {
        MindProfileService.Outcome out = service.submitPrefs(100L, List.of());
        assertThat(out.success()).isFalse();
        assertThat(out.code()).isEqualTo(1);
        verify(vectorStore, never()).upsert(anyLong(), any());
    }

    @Test
    void submitPrefsCreatesProfileAndVectorInOrder() {
        long accountId = 100L;
        when(llmClient.enrich(any())).thenReturn("{\"enriched\":true}");
        when(vectorStore.encode("{\"enriched\":true}")).thenReturn(new float[IVectorStore.DIM]);
        when(selfVectorRepository.get("accountId", accountId)).thenReturn(null);
        when(mindProfileRepository.get("accountId", accountId)).thenReturn(null);

        MindProfileService.Outcome out = service.submitPrefs(accountId, List.of("怀旧", "市井"));

        assertThat(out.success()).isTrue();
        assertThat(out.profileId()).isPositive();
        assertThat(out.vectorId()).isPositive();
        assertThat(out.enrichedPrefs()).isEqualTo("{\"enriched\":true}");

        // 先落 SelfVector 行，再写 embedding 向量列，最后落 MindProfile
        InOrder order = inOrder(selfVectorRepository, vectorStore, mindProfileRepository);
        order.verify(selfVectorRepository).add(any(SelfVector.class));
        order.verify(vectorStore).upsert(eq(accountId), any());
        order.verify(mindProfileRepository).add(any(MindProfile.class));

        ArgumentCaptor<SelfVector> svCaptor = ArgumentCaptor.forClass(SelfVector.class);
        verify(selfVectorRepository).add(svCaptor.capture());
        assertThat(svCaptor.getValue().getDim()).isEqualTo(IVectorStore.DIM);
        assertThat(svCaptor.getValue().getAccountId()).isEqualTo(accountId);
    }

    @Test
    void submitPrefsFallsBackWhenLlmFails() {
        long accountId = 200L;
        when(llmClient.enrich(any())).thenThrow(new RuntimeException("llm down"));
        when(vectorStore.encode(any())).thenReturn(new float[IVectorStore.DIM]);
        when(selfVectorRepository.get("accountId", accountId)).thenReturn(null);
        when(mindProfileRepository.get("accountId", accountId)).thenReturn(null);

        MindProfileService.Outcome out = service.submitPrefs(accountId, List.of("安静", "文艺"));

        // 兜底：enrichedPrefs 退回原始偏好 json（非空，不阻塞），流程仍成功
        assertThat(out.success()).isTrue();
        assertThat(out.enrichedPrefs()).contains("安静").contains("文艺");
        verify(vectorStore).upsert(eq(accountId), any());
    }

    @Test
    void submitPrefsUpdatesExistingProfileVersion() {
        long accountId = 300L;
        SelfVector existingVec = new SelfVector();
        existingVec.setId(555L);
        existingVec.setAccountId(accountId);
        MindProfile existingProfile = new MindProfile();
        existingProfile.setId(666L);
        existingProfile.setAccountId(accountId);
        existingProfile.setVersion(3);

        when(llmClient.enrich(any())).thenReturn("e");
        when(vectorStore.encode(any())).thenReturn(new float[IVectorStore.DIM]);
        when(selfVectorRepository.get("accountId", accountId)).thenReturn(existingVec);
        when(mindProfileRepository.get("accountId", accountId)).thenReturn(existingProfile);

        MindProfileService.Outcome out = service.submitPrefs(accountId, List.of("旅途"));

        assertThat(out.profileId()).isEqualTo(666L);
        assertThat(out.vectorId()).isEqualTo(555L);
        verify(selfVectorRepository).save(existingVec);
        verify(mindProfileRepository).save(existingProfile);
        assertThat(existingProfile.getVersion()).isEqualTo(4);
    }
}
