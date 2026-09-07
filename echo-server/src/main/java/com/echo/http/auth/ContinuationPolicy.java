package com.echo.http.auth;

/** Read-only boundary from Auth to the owning product domain. */
@FunctionalInterface
public interface ContinuationPolicy {
    boolean canResume(long accountId, String intent, String resourceId, String schemaVersion);

    static ContinuationPolicy noneOnly() {
        return (accountId, intent, resourceId, schemaVersion) -> "none".equals(intent);
    }
}
