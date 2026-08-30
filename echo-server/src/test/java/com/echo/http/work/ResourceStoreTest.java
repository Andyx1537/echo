package com.echo.http.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 素材归属的默认必须是「拒绝」。
 *
 * <p>本类锁的是一条<b>默认值</b>而不是一个功能：查不到归属时返回 false。
 * 这条默认一旦被改成 true（"查不到就放行，兼容一下历史素材"），
 * {@code POST /works} 的归属校验会<b>静默失效</b>——接口照常 200，
 * 谁也不会发现，直到有人拿别人的照片发了作品。见 {@code SPEC-security §4.14 E4}。</p>
 */
class ResourceStoreTest {

    /** 内存态（db == null），与无 PG 时的线上退化路径一致。 */
    private final ResourceStore store = new ResourceStore(null);

    @Test
    void 记过归属的素材属于上传者() {
        assertTrue(store.record("res-1", 100L, "k/res-1", "image/jpeg", 1024L, 1L));
        assertTrue(store.ownedBy("res-1", 100L));
    }

    @Test
    void 别人的素材判不通过() {
        store.record("res-1", 100L, "k/res-1", "image/jpeg", 1024L, 1L);
        assertFalse(store.ownedBy("res-1", 200L));
    }

    @Test
    void 没记过的素材一律判不通过() {
        // 🔴 这是本类存在的理由。历史素材（建表前上传的）会走到这里，
        //    "为了兼容"把它放行，等于把整个存储桶变成公共素材库。
        assertFalse(store.ownedBy("never-recorded", 100L));
    }

    @Test
    void 空值与非法账号判不通过() {
        assertFalse(store.ownedBy(null, 100L));
        assertFalse(store.ownedBy("", 100L));
        assertFalse(store.ownedBy("res-1", 0L));
        assertFalse(store.record(null, 100L, "k", "image/jpeg", 1L, 1L));
        assertFalse(store.record("res-2", 0L, "k", "image/jpeg", 1L, 1L));
    }
}
