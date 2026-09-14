package com.echo.http.behavior;

import com.aengine.util.id.IDGenerator;

/**
 * 服务端成功事实。客户端同名事件会被入口拒掉。
 */
public final class BehaviorLedger {
    static final String SERVER_SESSION = "server";

    private final BehaviorEventStore events;
    private final IDGenerator ids;

    public BehaviorLedger(BehaviorEventStore events, IDGenerator ids) {
        this.events = events;
        this.ids = ids;
    }

    public BehaviorEvent favoriteChanged(long accountId, long workId, boolean favorited) {
        return record(accountId, false, "work_favorite_changed", "work_detail", "work",
                String.valueOf(workId), BehaviorDictionary.PUBLIC,
                "srv:work_favorite_changed:" + accountId + ":" + workId + ":" + favorited);
    }

    public BehaviorEvent bindingCompleted(long accountId) {
        return record(accountId, false, "onboarding_binding_completed", "private_onboarding",
                "onboarding_session", String.valueOf(accountId), BehaviorDictionary.UI,
                "srv:onboarding_binding_completed:" + accountId);
    }

    public BehaviorEvent explicitFeedback(long accountId, boolean changed, String feedbackId) {
        String name = changed ? "explicit_feedback_changed" : "explicit_feedback_submitted";
        return record(accountId, false, name, "first_generation", "generation_result",
                feedbackId, BehaviorDictionary.PRIVATE, "srv:" + name + ":" + feedbackId);
    }

    public BehaviorEvent lessLikeThisChanged(long accountId, String targetId) {
        return record(accountId, false, "less_like_this_changed", "work_detail", "work",
                targetId, BehaviorDictionary.PUBLIC, "srv:less_like_this_changed:" + accountId + ":" + targetId);
    }

    public BehaviorEvent record(long accountId, boolean guest, String eventName, String surface,
                                String targetType, String targetId, String purpose, String idempotencyKey) {
        if (!BehaviorDictionary.serverEvent(eventName)) {
            return null;
        }
        BehaviorEvent existing = events.byIdempotency(accountId, idempotencyKey);
        if (existing != null) {
            return existing;
        }
        BehaviorEvent event = new BehaviorEvent();
        event.eventId = String.valueOf(ids.nextId());
        event.accountId = accountId;
        event.anonymousState = guest ? BehaviorEvent.ANONYMOUS : BehaviorEvent.BOUND;
        event.sessionId = SERVER_SESSION;
        event.eventName = eventName;
        event.surface = surface;
        event.targetType = targetType;
        event.targetId = targetId;
        event.contextJson = "{}";
        event.occurredAt = System.currentTimeMillis();
        event.receivedAt = event.occurredAt;
        event.schemaVersion = BehaviorDictionary.SCHEMA_VERSION;
        event.purposeCode = purpose;
        event.idempotencyKey = idempotencyKey;
        return events.putIfAbsent(event);
    }
}
