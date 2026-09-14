package com.echo.http.behavior;

/** 用户明确表达。改选留新行，不覆盖旧行。 */
public final class ExplicitFeedback {
    public static final String ACTIVE = "active";
    public static final String SUPERSEDED = "superseded";

    public String feedbackId;
    public long accountId;
    public String scope;
    public String targetType;
    public String targetId;
    public String questionCode;
    public String answerCode;
    public int answerVersion;
    public String sourceSurface;
    public long occurredAt;
    public String supersedesId;
    public String status = ACTIVE;
}
