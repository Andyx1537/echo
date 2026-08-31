package com.aengine.util.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * util.id 模块单元测试：IDGenerator（雪花算法）与 UUIDGenerator。
 */
class IdGeneratorTest {

    @Test
    void snowflakeIdsAreUniqueAndIncreasing() {
        IDGenerator generator = new IDGenerator(7);
        Set<Long> ids = new HashSet<>();
        long last = -1;
        for (int i = 0; i < 10000; i++) {
            long id = generator.nextId();
            assertThat(ids.add(id)).isTrue();
            assertThat(id).isGreaterThan(last);
            last = id;
        }
    }

    @Test
    void workerIdCanBeExtractedFromId() {
        IDGenerator generator = new IDGenerator(123);
        long id = generator.nextId();
        assertThat(IDGenerator.getWorkerIdFromID(id)).isEqualTo(123L);
    }

    @Test
    void invalidWorkerIdRejected() {
        assertThatThrownBy(() -> new IDGenerator(2048))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uuidGeneratorDelegatesToSupport() {
        UUIDGenerateSupport support = mock(UUIDGenerateSupport.class);
        // UUIDGenerator 内部使用的固定 key 常量为 "key_gen_a"
        when(support.genLong("key_gen_a")).thenReturn(987654321L);
        UUIDGenerator.init(support);
        assertThat(UUIDGenerator.getLongUUID()).isEqualTo(987654321L);
        assertThat(UUIDGenerator.getUUID()).isEqualTo("987654321");
    }
}
