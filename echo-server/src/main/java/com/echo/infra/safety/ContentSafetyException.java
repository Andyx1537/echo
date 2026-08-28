package com.echo.infra.safety;

/**
 * 内容安全服务调用失败。
 *
 * <p>🔴 抛出它<b>一定</b>导致「未通过」（{@link ContentSafetyGate} 统一收口）。
 * 所以实现里不要吞掉它、不要转成"通过"。</p>
 */
public class ContentSafetyException extends Exception {

    /** 失败原因分类，进指标维度，便于区分"供应商挂了"与"我们配错了"。 */
    public enum Reason {
        TIMEOUT,
        RATE_LIMITED,
        AUTH_FAILED,
        MALFORMED_RESPONSE,
        TRANSPORT,
        /** 本进程限流（配额用尽）。 */
        LOCAL_QUOTA,
        /** 熔断中，直接短路。 */
        CIRCUIT_OPEN
    }

    private final Reason reason;

    public ContentSafetyException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ContentSafetyException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
