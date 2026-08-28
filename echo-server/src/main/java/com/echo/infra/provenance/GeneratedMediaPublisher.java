package com.echo.infra.provenance;

import com.echo.infra.storage.IStorage;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 生成媒体落盘的<b>唯一</b>入口：先打隐式标识，再交给存储。
 *
 * <h2>这个类为什么现在就存在</h2>
 *
 * <p>Echo 当前<b>不生成任何图片/音频/视频</b>——定妆候选是渐变色名加 emoji，AI 只产出文本。
 * 所以今天没有一个文件需要被标识。但标识义务附着于「生成」这个动作本身，
 * 那意味着<b>第一条生成管线上线的那一刻</b>义务就成立了，而那一刻往往是赶工期的时候。</p>
 *
 * <p>把入口先立在这里，是为了让「生成了却没标识」在结构上做不到：生成侧要落盘就得走本类，
 * 走本类就必然带上标识，写不了标识就落不了盘。🔴 <b>不要给生成侧留一条直接调
 * {@link IStorage#put} 的近路。</b></p>
 *
 * <h2>🔴 不要拿它去处理用户上传的素材</h2>
 *
 * <p>上传的是用户自己的照片。给它打上 {@code Label=1} 是<b>一句假话</b>——
 * 声明一张真实的宠物照片是 AI 生成的，既误导用户也误导监管。用户上传走
 * {@code HttpGateway.handleUpload}，与本类无关，两条路不要合并。</p>
 */
@Slf4j
public final class GeneratedMediaPublisher {

    private final IStorage storage;
    private final ProvenanceConfig config;

    public GeneratedMediaPublisher(IStorage storage, ProvenanceConfig config) {
        this.storage = storage;
        this.config = config;
    }

    /**
     * 给 AI 生成的媒体打上隐式标识并落盘。
     *
     * @param resourceId  资源 ID；同时用作附录 E 的 {@code ProduceID}（我方对该内容的唯一编号）
     * @param data        生成产物的原始字节
     * @param contentType MIME
     * @param filename    文件名（推断扩展名用）
     * @throws IllegalStateException 服务提供者编码未配置——🔴 此时应当让生成失败，
     *                               而不是落一个没有标识的文件下去
     * @throws AigcMetadataWriter.UnsupportedMediaException 该格式还写不了标识
     */
    public IStorage.Stored publish(String resourceId, byte[] data, String contentType, String filename) {
        String producer = ProviderCode.require(config.providerCode());
        AigcLabel label = AigcLabel.firstWrite(producer, AigcLabel.sanitize(resourceId));
        byte[] labelled = AigcMetadataWriter.write(data, label);

        // 往返自检：写完立刻读回来。写入静默失败（改错了偏移、被下游库剥掉）在这里就该暴露，
        // 而不是等到监管核验时才发现文件是"看起来标了"
        String readBack = AigcMetadataWriter.read(labelled);
        if (readBack == null || !readBack.equals(label.toMetadataValue())) {
            throw new IllegalStateException("隐式标识写入后读回不一致，拒绝落盘 resourceId=" + resourceId);
        }
        log.info("[aigc] 已写入文件元数据隐式标识 resourceId={}, bytes={}→{}",
                resourceId, data.length, labelled.length);
        return storage.put(resourceId, labelled, contentType, filename);
    }
}
