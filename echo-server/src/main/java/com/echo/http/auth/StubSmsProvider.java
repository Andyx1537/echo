package com.echo.http.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit test/dev provider. Codes are observable only through this injected object. */
public final class StubSmsProvider implements SmsProvider {
    private final Map<String, String> codes = new ConcurrentHashMap<>();
    private volatile boolean available = true;

    @Override
    public void send(String e164Phone, String code, String requestId) {
        if (!available) throw new IllegalStateException("stub unavailable");
        codes.put(e164Phone, code);
    }

    @Override
    public boolean controllableStub() {
        return true;
    }

    public String codeFor(String e164Phone) {
        return codes.get(e164Phone);
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }
}
