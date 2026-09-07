package com.echo.http.auth;

/** Explicit local integration mode; no external SMS is sent. */
public final class DevelopmentSmsProvider implements SmsProvider {
    @Override public String createCode() { return "9999"; }
    @Override public void send(String phone, String code, String requestId) { }
    @Override public boolean controllableStub() { return true; }
}
