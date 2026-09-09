package com.echo.http.auth;

/** Fail-closed provider used until operations configures a real SMS implementation. */
public final class UnavailableSmsProvider implements SmsProvider {
    @Override
    public void send(String e164Phone, String code, String requestId) {
        throw new IllegalStateException("sms provider is not configured");
    }
}
