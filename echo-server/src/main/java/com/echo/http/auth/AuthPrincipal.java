package com.echo.http.auth;

/** Server-authoritative identity resolved from a persistent bearer session. */
public record AuthPrincipal(long accountId, String sessionId, String kind, String deviceCredentialId) {
    public boolean anonymous() {
        return "anonymous".equals(kind);
    }
}
