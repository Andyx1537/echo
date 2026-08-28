package com.echo.infra.vision;

import com.echo.infra.storage.IStorage;
import lombok.extern.slf4j.Slf4j;

import java.util.Base64;

/**
 * {@link IImageRefResolver} 的存储实现：{@code resourceId → 公网 URL 或压缩后的 data-uri}。
 *
 * <p>两条路径，按性价比优先：</p>
 * <ol>
 *   <li><b>公网 URL</b>（{@link IStorage#externalUrl(String)} 非空，生产 OSS/CDN 场景）——直接传 URL，
 *       让供应商自己去拉，省一次读盘和一大段 base64 上行；</li>
 *   <li><b>内联 data-uri</b>（{@link com.echo.infra.storage.LocalDiskStorage} 等本地/内网存储）——
 *       读字节 → {@link ImageCompressor} 瘦身 → {@code data:<mime>;base64,...}。</li>
 * </ol>
 *
 * <p><b>硬上限</b>：即便压缩回退（异形格式解不开），base64 后超过 {@value #HARD_LIMIT_BASE64_BYTES}
 * 字节也不发——那种包在供应商侧要跑一分钟以上，必然撞超时，不如直接走 fallback 让用户自己选，
 * 既快又诚实。</p>
 *
 * <p>全程 try-catch，任何异常都返回 {@code null}（走 fallback），建档流程不塌。</p>
 */
@Slf4j
public class StorageImageRefResolver implements IImageRefResolver {

    /** 发送前的硬上限（base64 后字节）：超过就放弃发送，直接 fallback，避免必然超时的长等待。 */
    public static final int HARD_LIMIT_BASE64_BYTES = 2 * 1024 * 1024;

    private final IStorage storage;
    private final int maxEdge;
    private final float quality;
    private final int targetBase64Bytes;

    public StorageImageRefResolver(IStorage storage) {
        this(storage, ImageCompressor.DEFAULT_MAX_EDGE, ImageCompressor.DEFAULT_QUALITY,
                ImageCompressor.DEFAULT_MAX_BASE64_BYTES);
    }

    /** 显式指定压缩参数（便于单测验证体积上限）。 */
    public StorageImageRefResolver(IStorage storage, int maxEdge, float quality, int targetBase64Bytes) {
        this.storage = storage;
        this.maxEdge = maxEdge;
        this.quality = quality;
        this.targetBase64Bytes = targetBase64Bytes;
    }

    @Override
    public String resolve(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            log.warn("Vision 资源解析失败: step=resolve, reason=resourceId 为空");
            return null;
        }
        String id = resourceId.trim();
        // 上层已经给了模型能直接吃的引用（联调直传外链/data-uri）就不再折腾
        if (isDirectRef(id)) {
            return id;
        }
        try {
            String external = storage.externalUrl(id);
            if (external != null && !external.isBlank()) {
                log.debug("Vision 资源解析: resourceId={} → 公网 URL（供应商自取）", id);
                return external;
            }
            IStorage.Loaded loaded = storage.loadByResourceId(id);
            if (loaded == null || loaded.data() == null || loaded.data().length == 0) {
                log.warn("Vision 资源解析失败: step=resolve, reason=素材不存在或为空, resourceId={}", id);
                return null;
            }
            ImageCompressor.Image image =
                    ImageCompressor.compress(loaded.data(), loaded.contentType(), maxEdge, quality, targetBase64Bytes);
            int base64Size = image.base64Size();
            if (base64Size > HARD_LIMIT_BASE64_BYTES) {
                log.warn("Vision 资源解析失败: step=compress, reason=压缩后仍超硬上限（{}B > {}B），"
                                + "发出去必然超时，直接走 fallback, resourceId={}, compressed={}",
                        base64Size, HARD_LIMIT_BASE64_BYTES, id, image.compressed());
                return null;
            }
            if (!image.compressed()) {
                log.warn("Vision 资源解析: step=compress, 压缩未生效（走安全回退，原图发送）, "
                        + "resourceId={}, mime={}, base64={}B", id, image.mime(), base64Size);
            }
            log.info("Vision 资源解析: resourceId={} → data-uri, mime={}, 原始={}B, 发送={}B(base64 {}B)",
                    id, image.mime(), loaded.data().length, image.data().length, base64Size);
            return toDataUri(image);
        } catch (Exception e) {
            log.warn("Vision 资源解析失败: step=resolve, resourceId={}, msg={}", id, e.toString());
            return null;
        }
    }

    /** 已经是模型可直接消费的引用（http(s) URL 或 data-uri）。 */
    private static boolean isDirectRef(String s) {
        String lower = s.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:");
    }

    private static String toDataUri(ImageCompressor.Image image) {
        String mime = (image.mime() == null || image.mime().isBlank()) ? "image/jpeg" : image.mime();
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image.data());
    }
}
