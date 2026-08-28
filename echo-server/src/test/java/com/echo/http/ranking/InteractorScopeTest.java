package com.echo.http.ranking;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 「独立互动者」口径：R1–R5 计入，R6/R7 关注类不计入。 */
class InteractorScopeTest {

    /** 🔴 关注不计入——否则一个关注者就能让卡永远流不掉。 */
    @Test
    void followsDoNotCountAsInteractions() {
        assertThat(InteractorScope.counts(InteractorScope.R7_FOLLOW_AUTHOR)).isFalse();
        assertThat(InteractorScope.counts(InteractorScope.R6_FOLLOW_TOPIC)).isFalse();
        assertThat(InteractorScope.COUNTED).doesNotContain("R6", "R7");
    }

    /** R1–R5 全部计入，含付费的 R5 献花。 */
    @Test
    void allFiveEchoTypesCountIncludingPaidFlowers() {
        assertThat(InteractorScope.COUNTED).containsExactlyInAnyOrder("R1", "R2", "R3", "R4", "R5");
        assertThat(InteractorScope.counts(InteractorScope.R5_SHARED_FLOWER)).isTrue();
    }

    /** 两个集合不重叠，且覆盖 R1–R7 全部七类——新增类型时这条会红，逼人表态。 */
    @Test
    void everyKnownTypeIsClassifiedExactlyOnce() {
        assertThat(InteractorScope.COUNTED).doesNotContainAnyElementsOf(InteractorScope.NOT_COUNTED);

        Set<String> all = new java.util.TreeSet<>(InteractorScope.COUNTED);
        all.addAll(InteractorScope.NOT_COUNTED);
        assertThat(all).containsExactly("R1", "R2", "R3", "R4", "R5", "R6", "R7");
    }

    /** 🔴 与数据库字典分叉时必须炸——两个数都还算得出来，不炸就没人会发现。 */
    @Test
    void divergenceFromTheDatabaseDictionaryIsFatal() {
        InteractorScope.assertMatchesDictionary(Set.of("R1", "R2", "R3", "R4", "R5"));

        assertThatThrownBy(() -> InteractorScope.assertMatchesDictionary(
                Set.of("R1", "R2", "R3", "R4", "R5", "R7")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已分叉")
                .hasMessageContaining("R7");

        assertThatThrownBy(() -> InteractorScope.assertMatchesDictionary(Set.of("R1", "R2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只在代码里");
    }
}
