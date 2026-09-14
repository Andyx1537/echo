package com.echo.http.behavior;

/** 行为事实。只记发生了什么，不记推断。 */
public final class BehaviorEvent {
    public static final String ANONYMOUS = "anonymous";
    public static final String BOUND = "bound";
    public static final String VALID = "valid";
    public static final String INVALID = "invalid";

    public String eventId;
    public long accountId;
    public String anonymousState;
    public String sessionId;
    public String eventName;
    public String surface;
    public String targetType;
    public String targetId;
    public Long activeDurationMs;
    public Long foregroundDurationMs;
    public Long loadWaitMs;
    public Integer attemptCount;
    public Integer backtrackCount;
    public String contextJson = "{}";
    public long occurredAt;
    public long receivedAt;
    public int schemaVersion;
    public String purposeCode;
    public String idempotencyKey;
    public String validityStatus = VALID;
    public String invalidReason;
}
