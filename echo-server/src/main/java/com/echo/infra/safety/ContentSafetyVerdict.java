package com.echo.infra.safety;

/**
 * 内容安全检测结果。
 *
 * @param passed   是否放行
 * @param label    命中的分类标签（涉政 / 涉黄 / 广告 …）；未命中为 null
 * @param outcome  这次判定<b>是怎么来的</b> —— 供指标与排障区分「真的检测过」与「按失败处理」
 */
public record ContentSafetyVerdict(boolean passed, String label, Outcome outcome) {

    public enum Outcome {
        /** 服务正常应答且判定通过。 */
        PASSED,
        /** 服务正常应答且判定命中。 */
        BLOCKED,
        /**
         * 🔴 服务异常（超时 / 限流 / 鉴权失败 / 返回格式异常 / 熔断中）→ <b>按未通过处理</b>。
         *
         * <p>与 {@link #BLOCKED} 分开记，是因为两者的运营动作完全不同：
         * {@code BLOCKED} 涨说明真有人在发违规内容；{@code FAILED_CLOSED} 涨说明<b>服务在出问题</b>，
         * 该去看供应商而不是去看内容。合并成一个数字，第二种情况会被当成第一种。</p>
         */
        FAILED_CLOSED,
        /**
         * ⚠️ 未配置服务 → 这一关<b>跳过</b>（不是通过）。
         *
         * <p>🔴 跳过与通过必须分开。跳过时能力算未就绪、{@code S13} 开关打不开，
         * 所以公开层根本没有用户自由文本——保护来自"功能没开"，不是来自"检测通过了"。
         * 若把跳过记成通过，报表上这一关会显示 100% 通过率，看起来运行良好。</p>
         */
        SKIPPED_UNCONFIGURED
    }

    public static ContentSafetyVerdict pass() {
        return new ContentSafetyVerdict(true, null, Outcome.PASSED);
    }

    public static ContentSafetyVerdict blocked(String label) {
        return new ContentSafetyVerdict(false, label, Outcome.BLOCKED);
    }

    /** 🔴 失败按未通过。 */
    public static ContentSafetyVerdict failedClosed(String reason) {
        return new ContentSafetyVerdict(false, reason, Outcome.FAILED_CLOSED);
    }

    /** ⚠️ 未配置：跳过本关。调用方据此不把它算成"检查过了"。 */
    public static ContentSafetyVerdict skipped() {
        return new ContentSafetyVerdict(true, null, Outcome.SKIPPED_UNCONFIGURED);
    }

    /** 这次判定是否真的经过了检测服务（用于区分"检查过并通过"与"没检查"）。 */
    public boolean actuallyInspected() {
        return outcome == Outcome.PASSED || outcome == Outcome.BLOCKED;
    }
}
