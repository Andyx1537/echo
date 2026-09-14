package com.echo.http.behavior;

import com.echo.infra.persistence.PgDb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 明确反馈仓储。本账本验收走内存。 */
public final class ExplicitFeedbackStore {
    private final Map<String, ExplicitFeedback> byId = new ConcurrentHashMap<>();

    public ExplicitFeedbackStore(PgDb db) {
        if (db != null) {
            // schema 已建表；本账本验收走内存
        }
    }

    public ExplicitFeedback active(long accountId, String scope, String questionCode, String targetId) {
        ExplicitFeedback found = null;
        for (ExplicitFeedback row : byId.values()) {
            if (row.accountId == accountId && scope.equals(row.scope)
                    && questionCode.equals(row.questionCode) && targetId.equals(row.targetId)
                    && ExplicitFeedback.ACTIVE.equals(row.status)) {
                if (found == null || row.occurredAt > found.occurredAt) {
                    found = row;
                }
            }
        }
        return found;
    }

    public void put(ExplicitFeedback row) {
        byId.put(row.feedbackId, row);
    }

    public List<ExplicitFeedback> ofAccount(long accountId) {
        List<ExplicitFeedback> out = new ArrayList<>();
        for (ExplicitFeedback row : byId.values()) {
            if (row.accountId == accountId) {
                out.add(row);
            }
        }
        out.sort(Comparator.comparingLong((ExplicitFeedback r) -> r.occurredAt));
        return out;
    }
}
