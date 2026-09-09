package com.echo.http.onboarding;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/** Atomic persistence boundary for an onboarding aggregate. */
public interface OnboardingRepository {
    OnboardingAggregate create(OnboardingAggregate aggregate);

    OnboardingAggregate find(String onboardingId);

    Map<String, Object> replay(String onboardingId, String idempotencyKey, String requestHash);

    Map<String, Object> mutate(String onboardingId, long accountId, long expectedVersion,
                               String idempotencyKey, String requestHash,
                               Mutation mutation);

    default Map<String, Object> mutate(String onboardingId, long accountId, long expectedVersion,
                                       String idempotencyKey, String requestHash,
                                       Function<OnboardingAggregate, Map<String, Object>> mutation) {
        return mutate(onboardingId, accountId, expectedVersion, idempotencyKey, requestHash,
                (aggregate, transaction) -> mutation.apply(aggregate));
    }

    boolean mutateSystem(String onboardingId, Predicate<OnboardingAggregate> mutation);

    @FunctionalInterface
    interface Mutation {
        Map<String, Object> apply(OnboardingAggregate aggregate, Connection transaction) throws SQLException;
    }
}
