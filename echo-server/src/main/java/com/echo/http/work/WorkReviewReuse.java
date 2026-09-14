package com.echo.http.work;

import com.echo.http.model.ModerationModels.CardStatus;
import com.echo.http.model.ModerationModels.MemoryCard;

/**
 * 内容变了、凭证过期或策略纪元变了，就不得拿旧结论直接公开。
 */
public final class WorkReviewReuse {

    public static final int CURRENT_POLICY_EPOCH = 1;

    private WorkReviewReuse() {
    }

    public static WorkReviewDecision decide(Work work, MemoryCard card, WorkReviewEvidence evidence, long now) {
        return decide(work, card, evidence, now, CURRENT_POLICY_EPOCH);
    }

    public static WorkReviewDecision decide(Work work, MemoryCard card, WorkReviewEvidence evidence,
                                           long now, int currentEpoch) {
        if (work.sourceCardId == null) {
            return WorkReviewDecision.full("user_upload");
        }
        if (card == null || card.deletedAt != null || sourceUnavailable(card.status)) {
            return WorkReviewDecision.hard("source_unavailable");
        }
        if (card.ownerId != work.authorId) {
            return WorkReviewDecision.hard("owner_mismatch");
        }
        if (evidence == null) {
            return WorkReviewDecision.full("evidence_missing");
        }
        if (evidence.ownerAccountId != work.authorId) {
            return WorkReviewDecision.hard("owner_mismatch");
        }
        if (evidence.consentRevoked) {
            return WorkReviewDecision.hard("consent_revoked");
        }
        if (evidence.consumedByWorkId != null) {
            return WorkReviewDecision.hard("evidence_consumed");
        }
        if (work.aiGenerated && !evidence.aigcLabelReady) {
            return WorkReviewDecision.hard("aigc_label_missing");
        }
        if (evidence.sourceCardId != work.sourceCardId) {
            return WorkReviewDecision.full("evidence_content_mismatch");
        }
        if (!WorkReviewEvidence.Result.PASSED.equals(evidence.result)
                || evidence.invalidatedAt != null
                || evidence.policyEpoch != currentEpoch) {
            return WorkReviewDecision.full("evidence_policy_invalid");
        }
        if (now >= evidence.expiresAt) {
            return WorkReviewDecision.full("evidence_expired");
        }
        if (!WorkContent.reviewHash(work).equals(nz(evidence.contentHash))) {
            return WorkReviewDecision.full("evidence_content_mismatch");
        }
        return WorkReviewDecision.reused(evidence);
    }

    public static String hardFailMessage(String reason) {
        return switch (reason) {
            case "consent_revoked" -> "这份授权已经收回，不能这样发出去。";
            case "resource_unavailable" -> "这份素材找不到了，重新选一次好吗？";
            case "source_unavailable" -> "这张卡已经不在了。";
            case "aigc_label_missing" -> "还缺一个生成标识，先补上再发。";
            case "evidence_consumed" -> "这份审核已经用过了。";
            default -> "这张卡找不到了。";
        };
    }

    private static boolean sourceUnavailable(String status) {
        return CardStatus.TAKENDOWN.equals(status)
                || CardStatus.DELETED.equals(status)
                || CardStatus.BLOCKED.equals(status);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
