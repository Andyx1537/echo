package com.echo.http.work;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 作品内容哈希与草稿版本。哈希只由服务端算，客户端不得决定审核哈希。
 */
public final class WorkContent {

    private WorkContent() {
    }

    public static String hash(Work work) {
        String raw = String.join("\n",
                nz(work.mediaType),
                nz(work.mediaKey),
                nz(work.posterKey),
                nz(work.title),
                nz(work.body),
                String.valueOf(work.width),
                String.valueOf(work.height),
                String.valueOf(work.durationMs),
                String.valueOf(work.aiGenerated),
                nz(work.visibility));
        return sha256(raw);
    }

    /**
     * 相对上次送审内容：没改就停在已送审版本，改了就只升一档，不每次保存都 +1。
     */
    public static void refreshDraftVersion(Work work) {
        String current = hash(work);
        work.contentHash = current;
        if (current.equals(nz(work.submittedContentHash))) {
            work.contentVersion = Math.max(1, work.submittedContentVersion);
        } else {
            work.contentVersion = Math.max(1, work.submittedContentVersion) + 1;
        }
    }

    public static void prepareResubmit(Work work) {
        if (work.contentVersion <= work.submittedContentVersion) {
            work.contentVersion = work.submittedContentVersion + 1;
        }
        work.contentHash = hash(work);
        work.submittedContentVersion = work.contentVersion;
        work.submittedContentHash = work.contentHash;
        work.status = Work.Status.PENDING;
    }

    public static String nextAction(Work work) {
        if (work == null) {
            return "none";
        }
        if (WorkSubmissionSlot.occupies(work.status)) {
            return "wait";
        }
        if (Work.Status.REJECTED.equals(work.status)) {
            return work.contentVersion > work.submittedContentVersion ? "resubmit" : "edit";
        }
        return "none";
    }

    private static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
