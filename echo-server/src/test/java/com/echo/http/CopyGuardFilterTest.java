package com.echo.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 词表过滤单测（定案 #6）：禁用词就地改写、红线不放行。
 */
class CopyGuardFilterTest {

    @Test
    void replacesHardDeathWords() {
        String out = CopyGuardFilter.sanitize("它去世了，永别了。");
        assertThat(out).doesNotContain("去世");
        assertThat(out).doesNotContain("永别");
        assertThat(CopyGuardFilter.hasBanned(out)).isFalse();
    }

    @Test
    void removesGuiltPhrases() {
        String out = CopyGuardFilter.sanitize("都怪你没来，它很难过。");
        assertThat(out).doesNotContain("都怪你");
        assertThat(out).doesNotContain("它很难过");
        assertThat(CopyGuardFilter.hasBanned(out)).isFalse();
    }

    @Test
    void removesRankingWords() {
        String out = CopyGuardFilter.sanitize("你排名第一，击败了所有人。");
        assertThat(out).doesNotContain("排名第");
        assertThat(out).doesNotContain("击败");
    }

    @Test
    void keepsGentleTextUntouched() {
        String gentle = "今天阳光很好，它在那边晒太阳。";
        assertThat(CopyGuardFilter.sanitize(gentle)).isEqualTo(gentle);
        assertThat(CopyGuardFilter.hasBanned(gentle)).isFalse();
    }

    @Test
    void nullAndEmptySafe() {
        assertThat(CopyGuardFilter.sanitize(null)).isNull();
        assertThat(CopyGuardFilter.sanitize("")).isEmpty();
    }
}
