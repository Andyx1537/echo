package com.echo.infra.vision;

import com.echo.infra.storage.IStorage;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link IVisionClient} 装配工厂：按 {@link VisionConfig}（{@code ECHO_VISION_PROVIDER} 等 env）选实现。
 *
 * <p>回退策略（对齐 {@link com.echo.infra.llm.LlmClientFactory}）：未配置 key 或 {@code provider=stub}
 * 时回落 {@link StubVisionClient}，保证无 key 也能编译、跑测试、本地联调；配置了真实供应商时用
 * {@link ApiVisionClient}，并以 stub 作为网络异常时的兜底委托。</p>
 */
@Slf4j
public final class VisionClientFactory {

    private VisionClientFactory() {
    }

    /** 从环境变量装配（不带资源解析，仅在无存储可用时使用）。 */
    public static IVisionClient fromEnv() {
        return create(VisionConfig.fromEnv(), new StubVisionClient(), null);
    }

    /**
     * 从环境变量装配，并接上按 {@code resourceId} 取图的资源解析器。
     *
     * @param storage 素材对象存储（用于把 resourceId 解析成公网 URL 或压缩后的 data-uri）
     */
    public static IVisionClient fromEnv(IStorage storage) {
        VisionConfig config = VisionConfig.fromEnv();
        IImageRefResolver resolver = storage == null ? null : new StorageImageRefResolver(
                storage, config.maxImageEdge(), ImageCompressor.DEFAULT_QUALITY,
                ImageCompressor.DEFAULT_MAX_BASE64_BYTES);
        return create(config, new StubVisionClient(), resolver);
    }

    /**
     * 按配置选实现。
     *
     * @param config   视觉识别配置
     * @param fallback 回退实现（既作 stub 分支的返回，也作 Api 分支的网络兜底委托）；null 时新建 {@link StubVisionClient}
     */
    public static IVisionClient create(VisionConfig config, IVisionClient fallback) {
        return create(config, fallback, null);
    }

    /**
     * 按配置选实现，并在真实分支外面套一层 {@link ResolvingVisionClient} 做资源解析。
     *
     * <p>stub 分支<b>不套</b>解析器：桩根本不看图，白读盘、白压一次图没有意义。</p>
     *
     * @param resolver 资源解析器；null 时真实分支直接把入参当 URL/data-uri（仅联调直传外链时够用）
     */
    public static IVisionClient create(VisionConfig config, IVisionClient fallback, IImageRefResolver resolver) {
        IVisionClient safeFallback = fallback != null ? fallback : new StubVisionClient();
        if (config.isStub()) {
            log.info("Vision 装配：provider={} 无有效 key/端点，回落 StubVisionClient（无 key 也可联调）。", config.provider());
            return safeFallback;
        }
        log.info("Vision 装配：provider={}, model={}, baseUrl={}（真实 qwen-vl 兼容端点，key 走 env 不入库）。",
                config.provider(), config.model(), config.baseUrl());
        ApiVisionClient api = new ApiVisionClient(config, safeFallback);
        if (resolver == null) {
            log.warn("Vision 装配：未提供资源解析器，detect 入参将被当作 URL/data-uri 直传（resourceId 会被供应商判为无效 URL）。");
            return api;
        }
        return new ResolvingVisionClient(resolver, api, safeFallback);
    }
}
