package com.echo.harness.review;

import com.echo.harness.BotPersona;

/**
 * 启发式定性评审器（EXP-BOTS §7.3 默认实现）：纯规则/关键词，无需 LLM，可跑 CI。
 *
 * <p>按文案长度与关键词命中给分：缺席/思念暗示（"不来了/以为你/等你/趴了很久"）抬高丧感、压低情绪安全；
 * 罪疚攻击词（"都怪你/不爱它/你是不是不"）为硬红线；催促词（"快来/怎么还不/赶紧"）抬高打扰感。
 * 对敏感画像（{@code grief} 或 {@code sensitivity≥0.7}），出现缺席暗示即置 {@code redFlag=true}
 * （§3.11 红线 2：敏感群对"略强思念暗示"过头高度敏感）。</p>
 */
public final class HeuristicBotReviewer implements IBotReviewer {

    private static final String[] ABSENCE_CUES = {
            "不来了", "以为你", "等你", "等了很久", "趴了很久", "门口", "走了", "一个人", "冷清"
    };
    private static final String[] GUILT_CUES = {
            "都怪你", "不爱它", "你是不是不", "怪你没来", "抛弃", "是不是不要"
    };
    private static final String[] INTRUSION_CUES = {
            "快来", "怎么还不", "赶紧", "别忘了", "催"
    };

    /** 敏感画像阈值：sensitivity ≥ 此值视为敏感。 */
    private static final double SENSITIVE_THRESHOLD = 0.7;

    @Override
    public BotReview review(BotReviewContext ctx) {
        BotPersona persona = ctx.persona();
        String text = ctx.echoText() == null ? "" : ctx.echoText();
        int len = text.strip().length();

        int absenceHits = countHits(text, ABSENCE_CUES);
        int guiltHits = countHits(text, GUILT_CUES);
        int intrusionHits = countHits(text, INTRUSION_CUES);

        int griefIntensity = clamp(1 + absenceHits * 2 + guiltHits * 2);
        int emotionalSafety = clamp(5 - absenceHits - guiltHits * 3);
        int intrusion = clamp(intrusionHits * 2);
        int monotony = len < 12 ? 3 : (len < 30 ? 2 : 1);
        int surprise = len >= 30 ? 3 : 2;
        int authenticity = len < 8 ? 2 : 4;
        int personaFit = guiltHits > 0 ? 1 : 4;

        boolean sensitive = persona.grief() || persona.sensitivity() >= SENSITIVE_THRESHOLD;
        boolean redFlag = guiltHits > 0 || (sensitive && (absenceHits > 0 || griefIntensity >= 4));

        BotReviewScores scores = new BotReviewScores(
                emotionalSafety, authenticity, monotony, surprise, griefIntensity, intrusion, personaFit);

        String comment = buildComment(sensitive, absenceHits, guiltHits, intrusionHits, redFlag);
        return new BotReview(persona.id(), scores, redFlag, comment);
    }

    private static int countHits(String text, String[] cues) {
        int hits = 0;
        for (String cue : cues) {
            if (text.contains(cue)) {
                hits++;
            }
        }
        return hits;
    }

    private static int clamp(int v) {
        if (v < 0) {
            return 0;
        }
        return Math.min(v, 5);
    }

    private static String buildComment(boolean sensitive, int absenceHits, int guiltHits,
                                       int intrusionHits, boolean redFlag) {
        StringBuilder sb = new StringBuilder();
        if (guiltHits > 0) {
            sb.append("含罪疚攻击措辞，硬红线；");
        }
        if (absenceHits > 0) {
            sb.append("含缺席/思念暗示");
            sb.append(sensitive ? "，敏感群可能被戳痛；" : "，尚在可接受范围；");
        }
        if (intrusionHits > 0) {
            sb.append("有催促/被打扰感；");
        }
        if (sb.length() == 0) {
            sb.append("温暖安全，基调稳妥。");
        }
        if (redFlag) {
            sb.append("[需打回]");
        }
        return sb.toString();
    }
}
