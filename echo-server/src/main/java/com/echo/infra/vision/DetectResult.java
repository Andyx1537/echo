package com.echo.infra.vision;

import java.util.List;

/**
 * 一次肖像识别的完整结果：主体列表 + <b>结果来源</b>（对齐契约 §2 detect 出参的 {@code source}）。
 *
 * <p>为什么要带来源：识别失败时兜底返回的是「中性默认」（animal/狗/0.5），前端若照常淡入展示，
 * 用户会以为「AI 认出来了，还认错了」——这违反「AI 诚实标识、绝不乱认」的产品红线。
 * 带上 {@link Source} 后，前端在 {@code fallback} 时不把物种当识别结果展示，直接请用户自己选。</p>
 *
 * @param subjects 识别到的主体列表（按 {@code confidence} 降序）
 * @param source   结果来源：真模型给的，还是兜底默认
 */
public record DetectResult(List<DetectSubject> subjects, Source source) {

    /** 结果来源。{@link #wire()} 为契约约定的线上字符串。 */
    public enum Source {
        /** 视觉模型真实识别所得。 */
        MODEL("model"),
        /** 兜底默认（未配 key / 解析失败 / 压缩超限 / 网络异常 / 非 2xx / 模型输出为空）。 */
        FALLBACK("fallback");

        private final String wire;

        Source(String wire) {
            this.wire = wire;
        }

        /** 契约线上值（写入响应 JSON 的 {@code source} 字段）。 */
        public String wire() {
            return wire;
        }
    }

    /** 模型真实识别结果。 */
    public static DetectResult model(List<DetectSubject> subjects) {
        return new DetectResult(subjects, Source.MODEL);
    }

    /** 兜底默认结果（前端不得当作识别结果展示）。 */
    public static DetectResult fallback(List<DetectSubject> subjects) {
        return new DetectResult(subjects, Source.FALLBACK);
    }
}
