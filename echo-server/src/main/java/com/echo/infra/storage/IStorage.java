package com.echo.infra.storage;

/**
 * 对象存储抽象（素材原件：肖像/音频/视频）。
 *
 * <p>插件化风格，与 {@code ILlmClient}/{@code IVisionClient}/{@code IVectorStore} 一致：
 * 业务只依赖本接口，具体实现按配置（{@link StorageConfig}）在 {@link StorageFactory} 装配。</p>
 *
 * <ul>
 *   <li>{@link LocalDiskStorage} —— 本地磁盘（dev / 单机），由 HTTP 网关 {@code /files/{key}} 下发；</li>
 *   <li>OSS / COS / MinIO(S3) —— 预留适配位（{@link StorageFactory} 中按 type 接入），
 *       其 {@code url} 通常直接是云对象 URL，无需网关下发。</li>
 * </ul>
 *
 * <p>素材原件是识别/生成/训练语料的前置；训练语料回流只保存这里的 {@code resourceId} 引用
 * （AI-CAPABILITIES §7 / PIPL：原件与去标识语料分离）。</p>
 */
public interface IStorage {

    /**
     * 保存一个对象。
     *
     * @param resourceId  业务侧生成的资源 ID（雪花 ID 字符串），用于拼 key、回流引用
     * @param data        原始字节（二进制安全）
     * @param contentType MIME 类型（可空，用于回读时设置响应头 / 推断扩展名）
     * @param filename    原始文件名（可空，用于推断扩展名）
     * @return 存储结果（resourceId / key / 可访问 url）
     */
    Stored put(String resourceId, byte[] data, String contentType, String filename);

    /**
     * 读取一个对象。
     *
     * @param key 存储键（{@link Stored#key()}）
     * @return 对象内容与 MIME；不存在返回 {@code null}
     */
    Loaded load(String key);

    /**
     * 按业务 {@code resourceId} 读取对象（由实现负责 {@code resourceId → key} 的定位，
     * 因为 key 通常带实现自行推断的扩展名，调用方只握有 {@code /upload} 返回的 resourceId）。
     *
     * <p>默认实现把 resourceId 直接当 key 试读，适用于 key 即 resourceId 的实现。</p>
     *
     * @param resourceId {@code POST /upload} 返回的资源 ID
     * @return 对象内容与 MIME；不存在返回 {@code null}
     */
    default Loaded loadByResourceId(String resourceId) {
        return load(resourceId);
    }

    /**
     * 该资源<b>对第三方服务端可直接拉取</b>的绝对 URL（OSS/COS/CDN 场景）。
     *
     * <p>用于视觉模型这类「我们给 URL、供应商自己去拉图」的调用：能给出公网 URL 就直接传 URL
     * （省一次读盘 + 省一大段 base64 上行带宽）；给不出就返回 {@code null}，由调用方回落到
     * 读字节内联 data-uri。</p>
     *
     * <p>默认返回 {@code null}（保守）：本地磁盘 / 内网地址对外部供应商不可达，误传只会换来一个
     * 「URL 无效」的 400。</p>
     *
     * @param resourceId {@code POST /upload} 返回的资源 ID
     * @return 公网可达的绝对 URL；不可达/不存在返回 {@code null}
     */
    default String externalUrl(String resourceId) {
        return null;
    }

    /** 存储结果。 */
    record Stored(String resourceId, String key, String url) {
    }

    /** 读取结果。 */
    record Loaded(byte[] data, String contentType) {
    }
}
