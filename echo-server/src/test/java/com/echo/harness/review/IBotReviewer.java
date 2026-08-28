package com.echo.harness.review;

/**
 * 定性评审器（EXP-BOTS §7.3 技术挂点）。
 *
 * <p>让每个画像 bot 以自己的视角"读"实际回声文案，产出主观评价，补充定量层看不到的问题
 * （太丧、太单调、太出戏、被催促感）。默认实现 {@link HeuristicBotReviewer} 无需 LLM、可跑 CI；
 * {@link LlmBotReviewer} 走 LLM（{@code com.echo.infra.llm.ILlmClient}）。</p>
 */
public interface IBotReviewer {

    /**
     * 评审一条回声文案。
     *
     * @param ctx 评审上下文（画像 + 温度档 + 文案）
     * @return 结构化评审结果
     */
    BotReview review(BotReviewContext ctx);
}
