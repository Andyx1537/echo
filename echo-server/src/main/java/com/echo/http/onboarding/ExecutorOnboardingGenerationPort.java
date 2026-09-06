package com.echo.http.onboarding;

import com.aengine.util.id.IDGenerator;
import com.echo.http.CopyGuardFilter;
import com.echo.infra.llm.ILlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/** Small provider adapter. It deliberately owns no product state. */
public final class ExecutorOnboardingGenerationPort implements OnboardingGenerationPort {
    private final ILlmClient llm;
    private final IDGenerator ids;
    private final Executor executor;

    public ExecutorOnboardingGenerationPort(ILlmClient llm, IDGenerator ids, Executor executor) {
        this.llm = llm;
        this.ids = ids;
        this.executor = executor;
    }

    @Override
    public void submit(String jobId, OnboardingAggregate.Anchor anchor, String adjustmentCode,
                       BiConsumer<List<OnboardingAggregate.Candidate>, Throwable> completion) {
        executor.execute(() -> {
            try {
                String raw = llm.complete("private-pet-onboarding\n" + anchor.answerSnapshot
                        + "\nadjustment=" + (adjustmentCode == null ? "" : adjustmentCode));
                List<OnboardingAggregate.Candidate> out = new ArrayList<>();
                String[] gradients = {"sunset", "meadow", "ocean"};
                String[] emojis = {"🐾", "🌤️", "✨"};
                for (int i = 0; i < 3; i++) {
                    OnboardingAggregate.Candidate c = new OnboardingAggregate.Candidate();
                    c.candidateId = String.valueOf(ids.nextId());
                    c.gradient = gradients[i];
                    c.emoji = emojis[i];
                    c.signature = CopyGuardFilter.sanitize(raw == null || raw.isBlank() || "{}".equals(raw)
                            ? "从熟悉的日常，慢慢认出它" : raw);
                    out.add(c);
                }
                completion.accept(out, null);
            } catch (Throwable error) {
                completion.accept(List.of(), error);
            }
        });
    }
}
