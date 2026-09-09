package com.echo.http.auth;

/** External SMS boundary. Production must never install a controllable stub. */
public interface SmsProvider {
    default String createCode() { return AuthCrypto.digits(6); }

    void send(String e164Phone, String code, String requestId) throws Exception;

    default boolean controllableStub() {
        return false;
    }
}
