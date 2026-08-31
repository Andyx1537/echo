package com.aengine.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cache 模块单元测试：SimpleCache / LRUCache / Element / CacheManager。
 */
class CacheTest {

    @Test
    void simpleCachePutGetRemove() {
        ICache<String, String> cache = new SimpleCache<>(0, 0);
        assertThat(cache.get("k")).isNull();
        cache.put("k", "v");
        assertThat(cache.containsKey("k")).isTrue();
        assertThat(cache.get("k")).isEqualTo("v");
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.remove("k")).isEqualTo("v");
        assertThat(cache.get("k")).isNull();
    }

    @Test
    void simpleCachePutIfAbsentReturnsExisting() {
        ICache<String, String> cache = new SimpleCache<>(0, 0);
        assertThat(cache.putIfAbsent("k", "first")).isNull();
        assertThat(cache.putIfAbsent("k", "second")).isEqualTo("first");
        assertThat(cache.get("k")).isEqualTo("first");
    }

    @Test
    void simpleCacheComputeIfAbsentInvokedOnce() {
        ICache<String, Integer> cache = new SimpleCache<>(0, 0);
        AtomicInteger calls = new AtomicInteger();
        Integer v1 = cache.computeIfAbsent("k", k -> calls.incrementAndGet());
        Integer v2 = cache.computeIfAbsent("k", k -> calls.incrementAndGet());
        assertThat(v1).isEqualTo(1);
        assertThat(v2).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void simpleCacheRejectsNull() {
        ICache<String, String> cache = new SimpleCache<>(0, 0);
        cache.put(null, "v");
        cache.put("k", null);
        assertThat(cache.size()).isZero();
    }

    @Test
    void simpleCacheEntryExpiresByTimeToLive() throws InterruptedException {
        ICache<String, String> cache = new SimpleCache<>(0, 1);
        cache.put("k", "v");
        assertThat(cache.get("k")).isEqualTo("v");
        Thread.sleep(1100);
        assertThat(cache.get("k")).isNull();
    }

    @Test
    void lruCacheEvictsLeastRecentlyUsed() {
        AtomicInteger evicted = new AtomicInteger();
        LRUCache<Integer, String> cache = new LRUCache<>(2, 0, 0,
                (k, v) -> evicted.incrementAndGet());
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");
        assertThat(cache.size()).isLessThanOrEqualTo(2);
        assertThat(evicted.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void elementExpireFlag() {
        Element<String, String> never = new Element<>("k", "v", 0, 0);
        assertThat(never.isExpire()).isFalse();
        assertThat(never.getKey()).isEqualTo("k");
        assertThat(never.getValue()).isEqualTo("v");
    }
}
