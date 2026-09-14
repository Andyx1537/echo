package com.echo.http.work;

/**
 * {@code POST /works} 对公开审核凭证的判定。硬失败不创建作品。
 */
public final class WorkReviewDecision {

    public static final String MODE_REUSED = "reused";
    public static final String MODE_FULL = "full";
    public static final String MODE_NONE = "none";

    public final String reviewMode;
    public final String status;
    public final String reasonCode;
    public final boolean hardFail;
    public final WorkReviewEvidence evidence;

    private WorkReviewDecision(String reviewMode, String status, String reasonCode,
                               boolean hardFail, WorkReviewEvidence evidence) {
        this.reviewMode = reviewMode;
        this.status = status;
        this.reasonCode = reasonCode;
        this.hardFail = hardFail;
        this.evidence = evidence;
    }

    public static WorkReviewDecision reused(WorkReviewEvidence evidence) {
        return new WorkReviewDecision(MODE_REUSED, Work.Status.PUBLIC, null, false, evidence);
    }

    public static WorkReviewDecision full(String reasonCode) {
        return new WorkReviewDecision(MODE_FULL, Work.Status.PENDING, reasonCode, false, null);
    }

    public static WorkReviewDecision hard(String reasonCode) {
        return new WorkReviewDecision(MODE_NONE, null, reasonCode, true, null);
    }
}
