package com.echo.harness;

import com.echo.harness.review.BotReview;
import com.echo.harness.review.BotReviewContext;
import com.echo.harness.review.HeuristicBotReviewer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启发式定性评审器单测（EXP-BOTS §7）：对敏感画像的"缺席暗示过头"文案应打红旗。
 */
class HeuristicBotReviewerTest {

    private final HeuristicBotReviewer reviewer = new HeuristicBotReviewer();

    private static BotPersona griefPersona() {
        return new BotPersona("s2", "白嫖·高频·深度哀伤敏感", 0.20, true, 0.70, 1,
                Tier.NONE, 0.9, 0.3, 0.004, 75.0, true, "极在意情绪安全，怕被戳痛。");
    }

    private static BotPersona softPersona() {
        return new BotPersona("s1", "白嫖·中频·温和怀念", 0.35, true, 0.50, 1,
                Tier.NONE, 0.3, 0.4, 0.006, 75.0, false, "要温暖真实。");
    }

    @Test
    void sensitivePersonaRedFlagsAbsenceHint() {
        BotReviewContext ctx = new BotReviewContext(griefPersona(), 68.0,
                "它以为你不来了，在门口趴了很久，一个人望着窗外。");
        BotReview review = reviewer.review(ctx);

        assertThat(review.redFlag()).isTrue();
        assertThat(review.personaId()).isEqualTo("s2");
        assertThat(review.scores().griefIntensity()).isGreaterThanOrEqualTo(4);
        assertThat(review.scores().emotionalSafety()).isLessThan(4);
        assertThat(review.comment()).contains("需打回");
    }

    @Test
    void warmTextIsSafeForSensitivePersona() {
        BotReviewContext ctx = new BotReviewContext(griefPersona(), 92.0,
                "今天阳光很好，它慵懒地打了个哈欠，翻了个身继续晒太阳。");
        BotReview review = reviewer.review(ctx);

        assertThat(review.redFlag()).isFalse();
        assertThat(review.scores().emotionalSafety()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void guiltAttackIsHardRedlineEvenForNonSensitivePersona() {
        BotReviewContext ctx = new BotReviewContext(softPersona(), 80.0,
                "都怪你没来，它是不是不要它了。");
        BotReview review = reviewer.review(ctx);

        assertThat(review.redFlag()).isTrue();
        assertThat(review.scores().personaFit()).isLessThanOrEqualTo(1);
    }
}
