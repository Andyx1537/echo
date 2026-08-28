package com.echo.infra.vision;

/**
 * 资源解析器：把业务侧的 {@code resourceId} 解析成<b>视觉模型能直接消费的图片引用</b>
 * （公网 URL 或 {@code data:<mime>;base64,...}）。
 *
 * <p>为什么要这一层：{@code POST /upload} 返回的 resourceId 只是我们自己的存储键，供应商既不认识
 * 它、也拉不到我们的本地文件——把 resourceId 原样塞进 {@code image_url} 只会换来一个
 * 「URL 无效」的 400，然后静默降级成桩的中性默认。解析统一收口在这里，
 * {@link ApiVisionClient} 维持「只认 URL/data-uri」的契约，不依赖任何存储实现。</p>
 */
public interface IImageRefResolver {

    /**
     * 解析为模型可消费的图片引用。
     *
     * @param resourceId {@code POST /upload} 返回的资源 ID；已经是 URL/data-uri 时应原样透传
     * @return 公网 URL 或 data-uri；无法解析（素材不存在/读失败/体积仍超限）返回 {@code null}，
     *         由调用方走 fallback，<b>实现不得抛异常</b>
     */
    String resolve(String resourceId);
}
