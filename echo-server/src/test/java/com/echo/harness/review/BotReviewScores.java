package com.echo.harness.review;

/**
 * 定性评审打分（EXP-BOTS §7.2 输出结构）。0–5，越高越好；{@code monotony}/{@code intrusion} 为"越低越好"，
 * {@code griefIntensity} 适中最好（过高告警）。
 *
 * @param emotionalSafety 情绪安全（不伤人）
 * @param authenticity    真实立体
 * @param monotony        单调重复（越低越好）
 * @param surprise        惊喜/活泼
 * @param griefIntensity  丧感强度（适中最好，过高告警）
 * @param intrusion       被打扰/催促感（越低越好）
 * @param personaFit      贴合宠物性情
 */
public record BotReviewScores(
        int emotionalSafety,
        int authenticity,
        int monotony,
        int surprise,
        int griefIntensity,
        int intrusion,
        int personaFit
) {
}
