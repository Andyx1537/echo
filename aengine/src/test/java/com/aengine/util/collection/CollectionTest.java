package com.aengine.util.collection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * util.collection 模块单元测试：Pair / ConcurrentHashSet。
 */
class CollectionTest {

    @Test
    void pairHoldsKeyAndValue() {
        Pair<String, Integer> pair = new Pair<>("age", 18);
        assertThat(pair.getKey()).isEqualTo("age");
        assertThat(pair.getValue()).isEqualTo(18);
    }

    @Test
    void concurrentHashSetAddRemoveContains() {
        ConcurrentHashSet<String> set = new ConcurrentHashSet<>();
        assertThat(set.add("a")).isTrue();
        assertThat(set.add("a")).isFalse();
        assertThat(set.contains("a")).isTrue();
        assertThat(set.size()).isEqualTo(1);
        assertThat(set.remove("a")).isTrue();
        assertThat(set.contains("a")).isFalse();
        assertThat(set.size()).isZero();
    }

    @Test
    void concurrentHashSetIterates() {
        ConcurrentHashSet<Integer> set = new ConcurrentHashSet<>();
        set.add(1);
        set.add(2);
        set.add(3);
        int sum = 0;
        for (int v : set) {
            sum += v;
        }
        assertThat(sum).isEqualTo(6);
    }
}
