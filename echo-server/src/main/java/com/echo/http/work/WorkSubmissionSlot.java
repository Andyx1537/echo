package com.echo.http.work;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** User-level single submission channel. Occupancy is a server fact, not a client guess. */
public final class WorkSubmissionSlot {
    private static final Set<String> OCCUPYING = Set.of("pending", "uploading", "submitting");

    private WorkSubmissionSlot() {
    }

    public static boolean occupies(String status) {
        return OCCUPYING.contains(status);
    }

    public static Map<String, Object> capability(Work blocking) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (blocking == null || !occupies(blocking.status)) {
            out.put("canSubmitWork", true);
            out.put("blockingWorkId", null);
            out.put("blockingStatus", null);
            out.put("nextAction", "none");
            return out;
        }
        out.put("canSubmitWork", false);
        out.put("blockingWorkId", String.valueOf(blocking.id));
        out.put("blockingStatus", blocking.status);
        out.put("nextAction", "wait");
        return out;
    }
}
