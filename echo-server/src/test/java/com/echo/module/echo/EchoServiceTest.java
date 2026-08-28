package com.echo.module.echo;

import com.aengine.util.GsonUtil;
import com.aengine.util.id.IDGenerator;
import com.echo.module.space.HostConfig;
import com.echo.module.space.MindSpace;
import com.echo.module.space.MindSpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回声服务单测（§4.3）：拉取(过期/可见性过滤)、留痕、过期清理。
 */
@ExtendWith(MockitoExtension.class)
class EchoServiceTest {

    @Mock
    private EchoRepository echoRepository;
    @Mock
    private MindSpaceRepository mindSpaceRepository;

    private EchoService service;

    @BeforeEach
    void setUp() {
        service = new EchoService(echoRepository, mindSpaceRepository, new IDGenerator(1));
    }

    @Test
    void leaveTraceCreatesEchoWithTtl() {
        long now = System.currentTimeMillis();
        Echo echo = service.leaveTrace(100L, 900L, "{\"gesture\":\"wave\"}", 60_000L);

        assertThat(echo.getOwnerSpaceId()).isEqualTo(900L);
        assertThat(echo.getFromAccountId()).isEqualTo(100L);
        assertThat(echo.getExpireAt()).isBetween(now + 60_000L - 1000, now + 60_000L + 5000);
        verify(echoRepository).add(echo);
    }

    @Test
    void leaveTraceUsesDefaultTtlWhenNonPositive() {
        Echo echo = service.leaveTrace(100L, 900L, "x", 0);
        long expected = System.currentTimeMillis() + EchoService.DEFAULT_TTL_MILLIS;
        assertThat(echo.getExpireAt()).isBetween(expected - 5000, expected + 5000);
    }

    @Test
    void leaveTraceRejectsBlankPayload() {
        assertThatThrownBy(() -> service.leaveTrace(100L, 900L, " ", 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pullEchoesReturnsEmptyWhenSpaceMissing() {
        when(mindSpaceRepository.get(900L)).thenReturn(null);
        assertThat(service.pullEchoes(100L, 900L)).isEmpty();
    }

    @Test
    void pullEchoesFiltersExpired() {
        long now = System.currentTimeMillis();
        MindSpace space = broadcastSpace(900L, 500L);
        when(mindSpaceRepository.get(900L)).thenReturn(space);

        Echo alive = echo(1L, now + 100_000L);
        Echo expired = echo(2L, now - 1000L);
        when(echoRepository.list("ownerSpaceId", 900L)).thenReturn(List.of(alive, expired));

        List<Echo> result = service.pullEchoes(100L, 900L);

        assertThat(result).containsExactly(alive);
    }

    @Test
    void pullEchoesRespectsNonBroadcastHostConfig() {
        // 非广播空间，主人=500，访客=100 → 不可见
        MindSpace space = new MindSpace();
        space.setId(900L);
        space.setAccountId(500L);
        space.setHostConfig(GsonUtil.beanToJson(new HostConfig(false, true, 0.5d, false)));
        when(mindSpaceRepository.get(900L)).thenReturn(space);

        assertThat(service.pullEchoes(100L, 900L)).isEmpty();
    }

    @Test
    void purgeExpiredDelegatesToRepository() {
        when(echoRepository.removeExpired(org.mockito.ArgumentMatchers.anyLong())).thenReturn(3);
        int n = service.purgeExpired();
        assertThat(n).isEqualTo(3);
        ArgumentCaptor<Long> nowCaptor = ArgumentCaptor.forClass(Long.class);
        verify(echoRepository).removeExpired(nowCaptor.capture());
        assertThat(nowCaptor.getValue()).isPositive();
    }

    private static MindSpace broadcastSpace(long id, long ownerAccountId) {
        MindSpace space = new MindSpace();
        space.setId(id);
        space.setAccountId(ownerAccountId);
        space.setHostConfig(GsonUtil.beanToJson(HostConfig.defaults()));
        return space;
    }

    private static Echo echo(long id, long expireAt) {
        Echo echo = new Echo();
        echo.setId(id);
        echo.setOwnerSpaceId(900L);
        echo.setExpireAt(expireAt);
        return echo;
    }
}
