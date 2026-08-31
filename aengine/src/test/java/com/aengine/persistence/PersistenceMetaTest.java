package com.aengine.persistence;

import com.aengine.persistence.annotation.Cache;
import com.aengine.persistence.annotation.Column;
import com.aengine.persistence.annotation.Index;
import com.aengine.persistence.annotation.Pk;
import com.aengine.persistence.annotation.Table;
import com.aengine.util.lock.ReferenceCountedLockManager;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * persistence 模块单元测试：注解 + 反射解析为 TableMeta / ColumnMeta，
 * CachedRepository 实体二级缓存逻辑，CacheIndex 状态机。
 */
class PersistenceMetaTest {

    @Test
    void parseTableMetaFromAnnotatedEntity() throws MetaException {
        TableMeta meta = TableMeta.parse(PlayerEntity.class);

        assertThat(meta.getName()).isEqualTo("t_player");
        assertThat(meta.getColumns()).hasSize(3);
        assertThat(meta.getPk()).isNotNull();
        assertThat(meta.getPk().getName()).isEqualTo("id");
        assertThat(meta.getPk().getType()).isEqualTo(TypeEnum.BIGINT);
        assertThat(meta.getColumnMetaByColumnName("name").getType()).isEqualTo(TypeEnum.VARCHAR);
        assertThat(meta.getColumnMetaByColumnName("level").getType()).isEqualTo(TypeEnum.INT);
        assertThat(meta.getIndexes()).containsKey("idx_name");
        assertThat(meta.getCache()).hasSize(1);
    }

    @Test
    void missingTableAnnotationThrows() {
        assertThat(catchMeta(NoTableEntity.class)).isInstanceOf(MetaException.class);
    }

    @Test
    void cachedRepositoryStoresAndRemovesByPk() throws MetaException {
        TableMeta meta = TableMeta.parse(PlayerEntity.class);
        CachedRepository<PlayerEntity> repo =
                new CachedRepository<>(meta, 0, 0, new ReferenceCountedLockManager<>());

        PlayerEntity player = new PlayerEntity();
        player.id = 100;
        player.name = "Alice";
        player.level = 9;

        repo.addCache(player);
        assertThat(repo.getFromCache("100")).isSameAs(player);

        repo.removeCache(player);
        assertThat(repo.getFromCache("100")).isNull();
    }

    @Test
    void cacheIndexCompletesAndDropsRemoved() {
        CacheIndex index = new CacheIndex(CacheIndex.CacheStatus.NOT_COMPLETE);
        assertThat(index.isComplete()).isFalse();
        index.add(1L);
        index.add(2L);
        index.remove(2L);

        index.complete(Arrays.asList(1L, 3L));

        assertThat(index.isComplete()).isTrue();
        assertThat(index.getIdentities()).containsOnlyKeys(1L, 3L);
        assertThat(index.getIdentities().values())
                .allMatch(s -> s == CacheIndex.IndexStatus.NORMAL);
    }

    private static Throwable catchMeta(Class<? extends AbstractEntity> clazz) {
        try {
            TableMeta.parse(clazz);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @Table(name = "t_player", comment = "玩家表",
            index = {@Index(name = "idx_name", columns = {"name"})},
            cache = {@Cache(columns = {"name"})})
    static class PlayerEntity implements AbstractEntity {
        @Pk
        @Column(name = "id")
        private long id;

        @Column(name = "name", length = 64, readOnly = true)
        private String name;

        @Column(name = "level")
        private int level;
    }

    static class NoTableEntity implements AbstractEntity {
        @Column(name = "x")
        private int x;
    }
}
