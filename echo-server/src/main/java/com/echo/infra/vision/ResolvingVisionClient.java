package com.echo.infra.vision;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * {@link IVisionClient} 装饰器：先把 {@code resourceId} 过 {@link IImageRefResolver} 解析成
 * 模型可消费的图片引用（公网 URL 或压缩后的 data-uri），再交给真实客户端识别。
 *
 * <p>这样 {@link ApiVisionClient} 维持「只认 URL/data-uri」的契约、不依赖任何存储实现，
 * 而上层 {@code EchoApi} 继续只传 resourceId，两头都不用改。</p>
 *
 * <p>解析失败（素材不存在 / 读失败 / 压缩后仍超硬上限）不发起网络调用，直接走 fallback 并如实标
 * {@link DetectResult.Source#FALLBACK}——省掉一次注定 400/超时的请求，也不会把兜底默认冒充识别结果。</p>
 */
@Slf4j
public class ResolvingVisionClient implements IVisionClient {

    private final IImageRefResolver resolver;
    private final IVisionClient delegate;
    private final IVisionClient fallback;

    /**
     * @param resolver 资源解析器
     * @param delegate 真实识别客户端（只认 URL/data-uri）
     * @param fallback 解析阶段就失败时的兜底；null 时新建 {@link StubVisionClient}
     */
    public ResolvingVisionClient(IImageRefResolver resolver, IVisionClient delegate, IVisionClient fallback) {
        this.resolver = resolver;
        this.delegate = delegate;
        this.fallback = fallback != null ? fallback : new StubVisionClient();
    }

    @Override
    public List<DetectSubject> detect(String resourceId) {
        return detectWithSource(resourceId).subjects();
    }

    @Override
    public DetectResult detectWithSource(String resourceId) {
        String ref;
        try {
            ref = resolver.resolve(resourceId);
        } catch (Exception e) {
            // 解析器契约上不该抛，这里再兜一层，保证建档流程不塌
            log.warn("Vision 资源解析异常，走 fallback: step=resolve, resourceId={}, msg={}", resourceId, e.toString());
            ref = null;
        }
        if (ref == null || ref.isBlank()) {
            log.warn("Vision 走 fallback: step=resolve, 无法解析出模型可消费的图片引用, resourceId={}", resourceId);
            return DetectResult.fallback(fallback.detect(resourceId));
        }
        return delegate.detectWithSource(ref);
    }
}
