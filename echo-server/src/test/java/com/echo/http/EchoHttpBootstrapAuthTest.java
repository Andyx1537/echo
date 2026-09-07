package com.echo.http;

import com.echo.http.auth.SmsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EchoHttpBootstrapAuthTest {
    @AfterEach
    void clearLegacyFlags() {
        System.clearProperty("echo.devRoutes");
        System.clearProperty("echo.sms.provider");
    }

    @Test
    void runtimeNeverEnablesTheControllableSmsStubFromDevelopmentFlags() {
        System.setProperty("echo.devRoutes", "true");
        System.setProperty("echo.sms.provider", "stub");

        SmsProvider provider = EchoHttpBootstrap.runtimeSmsProvider();

        assertThat(provider.controllableStub()).isFalse();
        assertThatThrownBy(() -> provider.send("+8613800000000", "123456", "request-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }
}
