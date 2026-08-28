package com.echo.http.exposure;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FeedRequestRegistry} 单实例前提的可发现性。
 *
 * <p>🔴 本轮<b>不改造</b>成共享缓存（现在是单实例，不值得为此引入 Redis），但这个前提必须
 * <b>在部署前被撞出来</b>，而不是上线后靠排查曝光数据异常才发现。</p>
 *
 * <p>⚠️ 环境变量不便在单测里改写，所以这里断言的是「未声明副本数时按单实例通过」与
 * 健康检查字段可见。多副本拒启动的路径靠部署演练验证（设 {@code ECHO_REPLICA_COUNT=2} 启动应失败）。</p>
 */
class FeedRequestRegistryMultiInstanceTest {

    /** 未声明副本数（现状）→ 启动期断言通过。 */
    @Test
    void passesWhenReplicaCountUndeclared() {
        assertThat(System.getenv(FeedRequestRegistry.ENV_REPLICA_COUNT))
                .as("本用例假定测试环境未声明副本数").isNull();
        FeedRequestRegistry.assertSingleInstance(); // 不抛即通过
    }

    /**
     * 健康检查必须把单实例前提暴露出来。
     *
     * <p>启动期断言只在启动那一刻有效，而副本数可能在之后被改——所以运行期也要能看出来。</p>
     */
    @Test
    void healthExposesSingleInstanceAssumption() {
        FeedRequestRegistry registry = new FeedRequestRegistry(ExposureConfig.forTest());
        registry.register(1L, FeedRequestRegistry.KIND_CARD, FeedRequestRegistry.SURFACE_IMMERSIVE, List.of("1001"), Set.of(), "recent", "warm");

        Map<String, Object> health = registry.health();
        assertThat(health)
                .containsEntry("singleInstanceAssumed", true)
                .containsEntry("declaredReplicas", 1)
                .containsEntry("snapshots", 1)
                .containsKeys("snapshotCapacity", "ttlSeconds");
    }
}
