package com.echo.http.auth;

/** Persistent bearer-token boundary used by the HTTP gateway. */
@FunctionalInterface
public interface SessionAuthenticator {
    AuthPrincipal authenticate(String token);
}
