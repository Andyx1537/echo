package com.echo.infra.storage;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link IStorage} 装配工厂：按 {@link StorageConfig#type()} 选实现。
 *
 * <p>本期实现 {@code local}；{@code oss/cos/minio} 为预留适配位——接入时在此 new 对应实现
 * （构造入参已在 {@link StorageConfig} 备好：endpoint/bucket/ak/sk/region）。选到未接入的 type
 * 会显式抛错（而非静默落本地，避免"以为传了云其实存在本机"）。</p>
 */
@Slf4j
public final class StorageFactory {

    private StorageFactory() {
    }

    public static IStorage create(StorageConfig cfg) {
        String type = cfg.type() == null ? "local" : cfg.type();
        switch (type) {
            case "local" -> {
                return new LocalDiskStorage(cfg.localDir(), cfg.baseUrl());
            }
            case "oss", "cos", "minio" -> throw new IllegalStateException(
                    "对象存储 type=" + type + " 适配位已预留但尚未接入；"
                            + "请实现对应 IStorage（endpoint/bucket/ak/sk/region 已在 StorageConfig 备好），"
                            + "或先用 ECHO_STORAGE_TYPE=local。");
            default -> {
                log.warn("未知 ECHO_STORAGE_TYPE={}，回落 local", type);
                return new LocalDiskStorage(cfg.localDir(), cfg.baseUrl());
            }
        }
    }
}
