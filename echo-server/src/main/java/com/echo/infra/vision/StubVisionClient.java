package com.echo.infra.vision;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * {@link IVisionClient} 的桩实现：默认返回<b>单主体</b>中性默认（{@code animal / 狗 / 0.5}）。
 *
 * <p>刻意<b>不做任何随机猜测</b>：前端旧版随机 mock 会把狗认成鹦鹉/刺猬，体验很糟；桩宁可给一个
 * 温和保守的默认，也绝不乱认——前端仍会以"就是 ta / 不是 ta？"让用户确认或用滚轮纠正，识别错误
 * 由此兜底，不阻断建档流程。</p>
 *
 * <p><b>诚实默认</b>：桩看不到图，<b>默认永远返回单主体</b>——绝不按 {@code resourceId} 奇偶等无关信号
 * 假装多主体，避免把单只宠物的照片误报成两个。多主体只在真实视觉模型确实识别到多个时才出现。</p>
 *
 * <p><b>多主体联调</b>：仅当显式开启（构造入参 {@code multiDemo=true}，或环境变量
 * {@code ECHO_VISION_STUB_MULTI=1}）时，桩才返回两个带 {@code box} 的主体（狗 + 猫，分居左右半区），
 * 用于本地演示"先选定单一主体"的护栏，与前端 {@code VITE_MOCK_MULTI} 行为对齐。真实实现由视觉模型给出。</p>
 *
 * <p>TODO（接真实视觉供应商）：本类是唯一接入点。后续把 {@link #detect(String)} 换成真实调用即可，
 * 上层（{@code EchoApi} 路由 / 装配）无需改动：</p>
 * <ol>
 *   <li>按 {@code resourceId} 从对象存储取回肖像字节（{@code POST /upload} 接真实 OSS 后可拿到 URL/流）；</li>
 *   <li>调用视觉模型：自建模型服务，或云图像识别 API（如阿里云图像识别 / 百度 AI 动物识别）；</li>
 *   <li>把供应商返回的每个检测框映射为 {@link DetectSubject}（标签对齐前端词表 {@code SPECIES_LIST}，
 *       无法归类降级为 {@code species="其他"}），并把像素框换算成归一化 {@link DetectSubject.Box}；</li>
 *   <li>按 {@code confidence} 降序返回；置信度整体偏低时前端会以更弱的淡入、更倾向让用户手动确认。</li>
 * </ol>
 * <p>保持零新增第三方依赖：真实实现建议用 JDK 自带 {@code java.net.http.HttpClient} + 现有 Gson。</p>
 */
@Slf4j
public class StubVisionClient implements IVisionClient {

    /** 中性默认物种（对齐前端词表首项，最常见的建档对象）。 */
    private static final String DEFAULT_SPECIES = "狗";

    /** 中性默认置信度：不高不低，既不误导也不阻断，交由用户确认。 */
    private static final double DEFAULT_CONFIDENCE = 0.5;

    /** 是否开启多主体演示（仅演示用；默认关闭，诚实单主体）。 */
    private final boolean multiDemo;

    /** 默认：读环境变量 {@code ECHO_VISION_STUB_MULTI=1} 决定是否开演示，缺省关闭。 */
    public StubVisionClient() {
        this("1".equals(System.getenv("ECHO_VISION_STUB_MULTI")));
    }

    /** 显式指定是否开多主体演示（便于单测）。 */
    public StubVisionClient(boolean multiDemo) {
        this.multiDemo = multiDemo;
    }

    @Override
    public List<DetectSubject> detect(String resourceId) {
        if (multiDemo) {
            log.debug("StubVisionClient.detect 返回多主体演示（显式开关）, resourceId={}", resourceId);
            // 已按 confidence 降序：狗在左半区、猫在右半区
            DetectSubject dog = new DetectSubject(
                    DetectSubject.SubjectType.ANIMAL, "狗", 0.6,
                    new DetectSubject.Box(0.05, 0.15, 0.40, 0.70));
            DetectSubject cat = new DetectSubject(
                    DetectSubject.SubjectType.ANIMAL, "猫", 0.5,
                    new DetectSubject.Box(0.55, 0.15, 0.40, 0.70));
            return List.of(dog, cat);
        }
        log.debug("StubVisionClient.detect 返回单主体中性默认（不随机猜测）, resourceId={}", resourceId);
        return List.of(DetectSubject.of(
                DetectSubject.SubjectType.ANIMAL, DEFAULT_SPECIES, DEFAULT_CONFIDENCE));
    }
}
