package com.echo.module.space;

import com.aengine.util.GsonUtil;
import com.aengine.util.id.IDGenerator;
import com.echo.module.mind.SelfVector;
import com.echo.module.mind.SelfVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 意识空间服务单测（§4.2）：选定势、生成/复用空间、更新主控配置。
 */
@ExtendWith(MockitoExtension.class)
class MindSpaceServiceTest {

    @Mock
    private MindSpaceRepository mindSpaceRepository;
    @Mock
    private SelfVectorRepository selfVectorRepository;

    private MindSpaceService service;

    @BeforeEach
    void setUp() {
        service = new MindSpaceService(mindSpaceRepository, selfVectorRepository, new IDGenerator(1));
    }

    @Test
    void enterSpaceCreatesWhenAbsentWithDefaultPresetAndHostConfig() {
        long accountId = 100L;
        when(mindSpaceRepository.get("accountId", accountId)).thenReturn(null);
        when(selfVectorRepository.get("accountId", accountId)).thenReturn(null); // 无向量 → 默认 1001

        MindSpace space = service.enterSpace(accountId);

        assertThat(space.getPresetSetId()).isEqualTo(PresetCatalog.DEFAULT_PRESET);
        assertThat(space.getAccountId()).isEqualTo(accountId);
        assertThat(space.getId()).isPositive();
        // hostConfig 为默认值
        HostConfig hc = GsonUtil.jsonToBean(space.getHostConfig(), HostConfig.class);
        assertThat(hc.isBroadcast()).isTrue();
        assertThat(hc.isAsyncOnly()).isTrue();
        assertThat(hc.getResonanceThreshold()).isEqualTo(0.5d);
        verify(mindSpaceRepository).add(space);
    }

    @Test
    void enterSpacePicksPresetByVectorHash() {
        long accountId = 101L;
        SelfVector sv = new SelfVector();
        sv.setAccountId(accountId);
        sv.setNormHash("deadbeef");
        when(mindSpaceRepository.get("accountId", accountId)).thenReturn(null);
        when(selfVectorRepository.get("accountId", accountId)).thenReturn(sv);

        MindSpace space = service.enterSpace(accountId);

        assertThat(space.getPresetSetId()).isEqualTo(PresetCatalog.choosePreset("deadbeef"));
        assertThat(space.getPresetSetId()).isIn(PresetCatalog.DUSK_CORRIDOR, PresetCatalog.STUDY_ROOM);
    }

    @Test
    void enterSpaceReusesExisting() {
        long accountId = 102L;
        MindSpace existing = new MindSpace();
        existing.setId(999L);
        existing.setAccountId(accountId);
        existing.setPresetSetId(PresetCatalog.STUDY_ROOM);
        when(mindSpaceRepository.get("accountId", accountId)).thenReturn(existing);

        MindSpace space = service.enterSpace(accountId);

        assertThat(space).isSameAs(existing);
        verify(mindSpaceRepository, never()).add(any(MindSpace.class));
    }

    @Test
    void updateHostConfigPersistsNewConfig() {
        long accountId = 103L;
        MindSpace existing = new MindSpace();
        existing.setId(1000L);
        existing.setAccountId(accountId);
        existing.setHostConfig(GsonUtil.beanToJson(HostConfig.defaults()));
        when(mindSpaceRepository.get("accountId", accountId)).thenReturn(existing);

        HostConfig updated = new HostConfig(false, false, 0.2d, false);
        MindSpace space = service.updateHostConfig(accountId, updated);

        HostConfig hc = GsonUtil.jsonToBean(space.getHostConfig(), HostConfig.class);
        assertThat(hc.isBroadcast()).isFalse();
        assertThat(hc.getResonanceThreshold()).isEqualTo(0.2d);
        verify(mindSpaceRepository).save(existing);
    }

    @Test
    void updateHostConfigCreatesSpaceWhenAbsent() {
        long accountId = 104L;
        when(mindSpaceRepository.get("accountId", accountId)).thenReturn(null);
        when(selfVectorRepository.get("accountId", accountId)).thenReturn(null);

        ArgumentCaptor<MindSpace> captor = ArgumentCaptor.forClass(MindSpace.class);
        service.updateHostConfig(accountId, new HostConfig(true, false, 0.9d, false));

        // 先创建（add）再更新（save）
        verify(mindSpaceRepository).add(captor.capture());
        verify(mindSpaceRepository).save(captor.getValue());
    }
}
