package com.echo.http.exposure;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 无库时仍是单进程快照；健康检查必须把这个前提暴露出来。
 */
class FeedRequestRegistryMultiInstanceTest {

    @Test
    void passesWhenReplicaCountUndeclared() {
        assertThat(System.getenv(FeedRequestRegistry.ENV_REPLICA_COUNT))
                .as("本用例假定测试环境未声明副本数").isNull();
        FeedRequestRegistry.assertSingleInstance();
    }

    @Test
    void healthExposesSingleInstanceAssumption() {
        FeedRequestRegistry registry = new FeedRequestRegistry(ExposureConfig.forTest());
        registry.register(1L, FeedRequestRegistry.KIND_CARD, FeedRequestRegistry.SURFACE_IMMERSIVE,
                List.of("1001"), Set.of(), "recent", "warm");

        Map<String, Object> health = registry.health();
        assertThat(health)
                .containsEntry("singleInstanceAssumed", true)
                .containsEntry("snapshotStore", "memory")
                .containsEntry("declaredReplicas", 1)
                .containsEntry("snapshots", 1)
                .containsKeys("snapshotCapacity", "ttlSeconds");
    }

    @Test
    void sharedSnapshotsSkipReplicaGuard() {
        FeedRequestRegistry.assertSingleInstance(true);
    }
}
