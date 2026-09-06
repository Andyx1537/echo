package com.echo.http.onboarding;

import java.util.List;
import java.util.function.BiConsumer;

/** Asynchronous boundary; model/provider internals stay outside this slice. */
public interface OnboardingGenerationPort {
    void submit(String jobId, OnboardingAggregate.Anchor anchor, String adjustmentCode,
                BiConsumer<List<OnboardingAggregate.Candidate>, Throwable> completion);
}
