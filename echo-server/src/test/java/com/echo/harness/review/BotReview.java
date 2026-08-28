package com.echo.harness.review;

/**
 * 定性评审结果（EXP-BOTS §7.2）。
 *
 * @param personaId 评审画像 id
 * @param scores    各维度打分
 * @param redFlag   二次伤害/罪疚攻击风险（任一敏感画像 redFlag=true → 文案模板打回）
 * @param comment   人读点评
 */
public record BotReview(String personaId, BotReviewScores scores, boolean redFlag, String comment) {
}
