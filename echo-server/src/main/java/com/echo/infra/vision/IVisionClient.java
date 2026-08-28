package com.echo.infra.vision;

import java.util.List;

/**
 * 肖像识别（视觉）客户端抽象（对应 API-CONTRACT §2 {@code POST /pet/onboarding/detect}）。
 *
 * <p>建档第 1 步"上传一张肖像 → 顺路认出种类"的服务端入口。抽象成接口，沿用
 * {@link com.echo.infra.llm.ILlmClient}/{@link com.echo.infra.vector.IVectorStore} 的插件化风格：
 * 接口 + 可替换实现，便于切换供应商（自建模型服务 ↔ 云图像识别 API）、控频次/超时，并便于单测 mock
 * （外部服务一律 mock）。</p>
 *
 * <p>本期提供 {@link StubVisionClient} 桩实现返回中性默认，真实 HTTP 实现待供应商选型后落地。</p>
 */
public interface IVisionClient {

    /**
     * 按已上传的肖像资源 id 做识别，支持一张图多个主体。
     *
     * @param resourceId {@code POST /upload} 返回的肖像资源 id（当前占位存储）
     * @return 识别到的主体列表，<b>按 {@code confidence} 降序</b>；空列表表示未认出。
     *         实现方在无法归类时应返回中性默认而非乱认。
     */
    List<DetectSubject> detect(String resourceId);

    /**
     * 同 {@link #detect(String)}，但额外标明结果<b>来自真模型还是兜底默认</b>。
     *
     * <p>默认实现保守地标为 {@link DetectResult.Source#FALLBACK}：看不出来源时宁可说「这是兜底」，
     * 也不能让前端把中性默认当成识别结果展示（诚实标识红线）。真实实现须覆写并如实标注。</p>
     *
     * @param resourceId 同 {@link #detect(String)}
     * @return 带来源标记的识别结果
     */
    default DetectResult detectWithSource(String resourceId) {
        return DetectResult.fallback(detect(resourceId));
    }
}
