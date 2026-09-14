package com.echo.http.work;

/**
 * 公开级审核凭证。生成阶段「允许公开」的结论，不是「私域可送达」。
 */
public final class WorkReviewEvidence {

    public static final class Result {
        public static final String PASSED = "passed";
        public static final String RESTRICTED = "restricted";
        public static final String FAILED = "failed";

        private Result() {
        }
    }

    public static final long DEFAULT_TTL_MS = 90L * 24 * 60 * 60 * 1000;

    public long reviewEvidenceId;
    public long sourceCardId;
    public int sourceContentVersion = 1;
    public String result = Result.PASSED;
    public String contentHash = "";
    public long ownerAccountId;
    public String policyVersion = "";
    public int policyEpoch = WorkReviewReuse.CURRENT_POLICY_EPOCH;
    public long reviewedAt;
    public long expiresAt;
    public boolean aigcLabelReady = true;
    public boolean consentRevoked;
    public Long consumedByWorkId;
    public Long invalidatedAt;
    public String invalidationReason;

    public static WorkReviewEvidence passed(long id, long cardId, long ownerId, String contentHash, long now) {
        WorkReviewEvidence evidence = new WorkReviewEvidence();
        evidence.reviewEvidenceId = id;
        evidence.sourceCardId = cardId;
        evidence.ownerAccountId = ownerId;
        evidence.contentHash = contentHash == null ? "" : contentHash;
        evidence.result = Result.PASSED;
        evidence.reviewedAt = now;
        evidence.expiresAt = now + DEFAULT_TTL_MS;
        evidence.aigcLabelReady = true;
        return evidence;
    }
}
