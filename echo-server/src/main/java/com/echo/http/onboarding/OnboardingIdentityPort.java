package com.echo.http.onboarding;

/** Read-only boundary to the identity system. */
@FunctionalInterface
public interface OnboardingIdentityPort {
    boolean phoneBound(long accountId);
}
