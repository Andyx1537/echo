package com.echo.http.onboarding;

import com.aengine.util.id.IDGenerator;
import com.echo.infra.llm.MockLlmClient;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OnboardingGenerationPreviewTest {
    @Test
    void developmentPreviewDoesNotExposeReviewerJsonAndKeepsReviewerContract() {
        MockLlmClient llm = new MockLlmClient();
        assertThat(llm.complete("review content")).contains("\"scores\"");
        OnboardingAggregate.Anchor anchor = new OnboardingAggregate.Anchor();
        new ExecutorOnboardingGenerationPort(llm, new IDGenerator(63), Runnable::run)
                .submit("preview-test", anchor, null, (candidates, error) -> {
                    assertThat(error).isNull();
                    assertThat(candidates).hasSize(3).allSatisfy(candidate -> {
                        assertThat(candidate.signature).contains("开发流程预览").doesNotContain("scores");
                        assertThat(candidate.gradient).startsWith("linear-gradient(");
                    });
                });
    }
}
