package com.echo.infra.persistence;

import com.aengine.persistence.AbstractEntity;
import com.aengine.persistence.annotation.CRepository;
import com.aengine.persistence.annotation.Cache;
import com.aengine.persistence.annotation.Column;
import com.aengine.persistence.annotation.Index;
import com.aengine.persistence.annotation.Pk;
import com.aengine.persistence.annotation.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CachedPgRepository} 缓存命中/回源逻辑单测。
 *
 * <p>用 mock 的 {@link PgDb} 替代真实 Postgres：构造期 fixTable 走 mock，
 * 之后验证 get/list 首次回源（查 DB）、二次命中缓存（不再查 DB）。</p>
 */
class CachedPgRepositoryTest {

    private PgDb db;
    private CacheTestRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        db = mock(PgDb.class);
        // 构造期 fixTable 与之后的 SELECT 都走 2 参数 query；按 SQL 内容路由返回
        when(db.query(anyString(), any())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("information_schema")) {
                return new ArrayList<Map<String, Object>>(); // 表不存在 -> 触发建表
            }
            if (sql.contains("WHERE")) {
                return List.of(row(1L, "Alice", 10)); // 回源命中一行
            }
            return new ArrayList<Map<String, Object>>();
        });
        // 单例注册表跨用例复用：先移除上一个用例注册的 mock，再注册本用例的新 mock
        PgDbManager.getInstance().remove("echo_test");
        PgDbManager.getInstance().add(new PgDbHolder("echo_test", db).pgDb());
        repository = new CacheTestRepository();
    }

    @Test
    void getReturnsFromDbThenServesFromCache() throws Exception {
        CacheTestEntity first = repository.get(1L);
        assertThat(first).isNotNull();
        assertThat(first.id).isEqualTo(1L);
        assertThat(first.name).isEqualTo("Alice");
        // 已进入二级缓存
        assertThat(repository.peekCache(1L)).isSameAs(first);

        CacheTestEntity second = repository.get(1L);
        // 命中缓存：返回同一实例，且 DB 只被回源一次
        assertThat(second).isSameAs(first);
        verify(db, times(1)).query(argThat(sql -> sql != null && sql.contains("WHERE \"id\"")), any());
    }

    @Test
    void listByIndexCachesThenServesFromCache() throws Exception {
        List<CacheTestEntity> firstList = repository.list("name", "Alice");
        assertThat(firstList).hasSize(1);
        assertThat(firstList.get(0).name).isEqualTo("Alice");

        List<CacheTestEntity> secondList = repository.list("name", "Alice");
        assertThat(secondList).hasSize(1);
        assertThat(secondList.get(0)).isSameAs(firstList.get(0));

        // 按索引回源仅一次（第二次走索引缓存 + 实体缓存）
        verify(db, times(1)).query(argThat(sql -> sql != null && sql.contains("WHERE \"name\"")), any());
    }

    private static Map<String, Object> row(long id, String name, int score) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("score", score);
        return map;
    }

    // 简单包装，便于用具名数据源注册 mock（PgDb.getName 返回构造名）
    private record PgDbHolder(String name, PgDb delegate) {
        PgDb pgDb() {
            when(delegate.getName()).thenReturn(name);
            return delegate;
        }
    }

    @Table(name = "t_cache_test", comment = "缓存测试表",
            index = {@Index(name = "idx_name", columns = {"name"})},
            cache = {@Cache(columns = {"name"})})
    static class CacheTestEntity implements AbstractEntity {
        @Pk(auto = false)
        @Column(name = "id")
        long id;

        @Column(name = "name", length = 64, readOnly = true)
        String name;

        @Column(name = "score")
        int score;
    }

    @CRepository(source = "echo_test")
    static class CacheTestRepository extends CachedPgRepository<CacheTestEntity> {
    }
}
