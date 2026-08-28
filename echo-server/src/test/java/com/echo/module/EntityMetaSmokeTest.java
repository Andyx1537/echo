package com.echo.module;

import com.aengine.persistence.AbstractEntity;
import com.aengine.persistence.MetaException;
import com.aengine.persistence.TableMeta;
import com.aengine.persistence.TypeEnum;
import com.echo.module.account.Account;
import com.echo.module.avatar.Avatar;
import com.echo.module.echo.Echo;
import com.echo.module.mind.MindProfile;
import com.echo.module.mind.SelfVector;
import com.echo.module.resonance.ResonanceRecord;
import com.echo.module.social.Friendship;
import com.echo.module.social.Stall;
import com.echo.module.space.MindSpace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体 TableMeta 解析 smoke 测试（参考 Aengine PersistenceMetaTest 思路）。
 *
 * <p>{@link TableMeta#parse(Class)} 以 {@code autoCacheIndex=false} 解析，会强校验
 * "被 @Cache 的列必须 readOnly"——任一实体违反约束都会在此抛 {@link MetaException}，
 * 从而保证 §2 的缓存约束在编译期外再被测试兜住。</p>
 */
class EntityMetaSmokeTest {

    private static final List<Class<? extends AbstractEntity>> ENTITIES = List.of(
            Account.class, Avatar.class, MindProfile.class, SelfVector.class,
            MindSpace.class, ResonanceRecord.class, Echo.class, Friendship.class, Stall.class);

    @Test
    void allEntitiesParseWithSnowflakePk() throws MetaException {
        for (Class<? extends AbstractEntity> clazz : ENTITIES) {
            TableMeta meta = TableMeta.parse(clazz);
            assertThat(meta.getName()).as("table name of %s", clazz.getSimpleName()).startsWith("t_");
            assertThat(meta.getPk()).as("pk of %s", clazz.getSimpleName()).isNotNull();
            assertThat(meta.getPk().getName()).isEqualTo("id");
            assertThat(meta.getPk().getType()).isEqualTo(TypeEnum.BIGINT);
            // 雪花 ID 主键非自增
            assertThat(meta.getPk().isAuto()).as("pk auto of %s", clazz.getSimpleName()).isFalse();
            assertThat(meta.getColumns()).as("columns of %s", clazz.getSimpleName()).isNotEmpty();
        }
    }

    @Test
    void accountMetaMatchesSpec() throws MetaException {
        TableMeta meta = TableMeta.parse(Account.class);

        assertThat(meta.getName()).isEqualTo("t_account");
        assertThat(meta.getColumnMetaByColumnName("openId").getType()).isEqualTo(TypeEnum.VARCHAR);
        assertThat(meta.getColumnMetaByColumnName("openId").isReadOnly()).isTrue();
        assertThat(meta.getColumnMetaByColumnName("status").getType()).isEqualTo(TypeEnum.INT);
        assertThat(meta.getColumnMetaByColumnName("createTime").getType()).isEqualTo(TypeEnum.BIGINT);
        assertThat(meta.getIndexes()).containsKey("uk_open_id");
        assertThat(meta.getCache()).hasSize(1);
    }

    @Test
    void echoHasOwnerSpaceAndExpireIndexes() throws MetaException {
        TableMeta meta = TableMeta.parse(Echo.class);

        assertThat(meta.getName()).isEqualTo("t_echo");
        assertThat(meta.getIndexes()).containsKeys("idx_owner_space_id", "idx_expire_at");
        // json payload 以 TEXT 落库
        assertThat(meta.getColumnMetaByColumnName("payload").getType()).isEqualTo(TypeEnum.TEXT);
    }

    @Test
    void friendshipHasCompositeUniqueIndex() throws MetaException {
        TableMeta meta = TableMeta.parse(Friendship.class);

        assertThat(meta.getName()).isEqualTo("t_friendship");
        assertThat(meta.getIndexes()).containsKeys("uk_account_peer", "idx_account_id");
        assertThat(meta.getIndexes().get("uk_account_peer").getColumns())
                .containsExactly("accountId", "peerId");
    }
}
