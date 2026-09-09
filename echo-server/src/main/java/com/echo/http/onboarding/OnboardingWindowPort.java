package com.echo.http.onboarding;

import java.sql.Connection;
import java.sql.SQLException;

/** Boundary that creates the single private window after a successful CAS confirmation. */
public interface OnboardingWindowPort {
    Result confirm(OnboardingAggregate session, OnboardingAggregate.Candidate candidate,
                   Connection transaction) throws SQLException;

    record Result(String petId, String windowId) { }
}
