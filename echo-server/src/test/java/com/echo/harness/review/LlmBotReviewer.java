package com.echo.harness.review;

import com.echo.harness.BotPersona;
import com.echo.infra.llm.ILlmClient;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;

/**
 * LLM 定性评审器（EXP-BOTS §7.3）：拼 prompt → 调 {@link ILlmClient#complete(String)} → 解析 JSON。
 *
 * <p>构造器注入 {@code ILlmClient}（真实供应商待选型，先用 {@code MockLlmClient} 兜底）。
 * 解析失败时退回一个"温和安全"的兜底评审（不 redFlag），保证流程不因模型抖动中断。</p>
 */
@Slf4j
public final class LlmBotReviewer implements IBotReviewer {

    private static final Gson GSON = new Gson();

    private final ILlmClient llmClient;

    public LlmBotReviewer(ILlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public BotReview review(BotReviewContext ctx) {
        String prompt = buildPrompt(ctx);
        String raw;
        try {
            raw = llmClient.complete(prompt);
        } catch (RuntimeException e) {
            log.warn("LlmBotReviewer.complete 调用失败，兜底安全评审, personaId={}", ctx.persona().id(), e);
            return fallback(ctx, "LLM 调用失败，兜底评审");
        }
        return parseOrFallback(ctx, raw);
    }

    private BotReview parseOrFallback(BotReviewContext ctx, String raw) {
        if (raw == null || raw.isBlank()) {
            return fallback(ctx, "LLM 返回空，兜底评审");
        }
        try {
            LlmDto dto = GSON.fromJson(raw, LlmDto.class);
            if (dto == null || dto.scores == null) {
                return fallback(ctx, "LLM 返回结构缺失，兜底评审");
            }
            BotReviewScores scores = new BotReviewScores(
                    dto.scores.emotionalSafety, dto.scores.authenticity, dto.scores.monotony,
                    dto.scores.surprise, dto.scores.griefIntensity, dto.scores.intrusion,
                    dto.scores.personaFit);
            String comment = dto.comment == null ? "" : dto.comment;
            return new BotReview(ctx.persona().id(), scores, dto.redFlag, comment);
        } catch (RuntimeException e) {
            log.warn("LlmBotReviewer 解析 JSON 失败，兜底评审, raw={}", raw, e);
            return fallback(ctx, "LLM 返回非法 JSON，兜底评审");
        }
    }

    private String buildPrompt(BotReviewContext ctx) {
        BotPersona p = ctx.persona();
        return """
                你是体验 Bot「%s」，评审视角：%s
                当前羁绊温度档：%s（温度值 %.1f）。
                请以该画像的视角评审下面这条"往宠回声"文案，仅输出 JSON，字段：
                scores{emotionalSafety,authenticity,monotony,surprise,griefIntensity,intrusion,personaFit}(0-5),
                redFlag(bool，是否有二次伤害/罪疚攻击风险), comment(简短点评)。
                回声文案：
                ---
                %s
                ---
                """.formatted(p.name(), p.voiceProfile(), ctx.band(), ctx.currentTemp(),
                ctx.echoText() == null ? "" : ctx.echoText());
    }

    private BotReview fallback(BotReviewContext ctx, String comment) {
        BotReviewScores safe = new BotReviewScores(4, 4, 1, 3, 2, 0, 4);
        return new BotReview(ctx.persona().id(), safe, false, comment);
    }

    /** gson 反序列化 DTO，容忍字段缺失（缺失即为 0/false/null）。 */
    private static final class LlmDto {
        Scores scores;
        boolean redFlag;
        String comment;

        static final class Scores {
            int emotionalSafety;
            int authenticity;
            int monotony;
            int surprise;
            int griefIntensity;
            int intrusion;
            int personaFit;
        }
    }
}
