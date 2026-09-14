package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.behavior.AdaptationProfileStore;
import com.echo.http.behavior.BehaviorDictionary;
import com.echo.http.behavior.BehaviorEvent;
import com.echo.http.behavior.BehaviorEventStore;
import com.echo.http.behavior.BehaviorLedger;
import com.echo.http.behavior.ExplicitFeedback;
import com.echo.http.behavior.ExplicitFeedbackStore;
import com.echo.http.behavior.FeedbackDictionary;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.store.EchoStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 0 行为事实入口。收下不等于拿去排序。
 */
public final class BehaviorApi {
    static final int MAX_BATCH = 50;

    private final BehaviorEventStore events;
    private final EchoStore accounts;
    private final IDGenerator ids;
    private final ExplicitFeedbackStore feedbacks;
    private final BehaviorLedger ledger;
    private final AdaptationProfileStore profiles;

    public BehaviorApi(BehaviorEventStore events, EchoStore accounts, IDGenerator ids) {
        this(events, accounts, ids, new ExplicitFeedbackStore(null), new BehaviorLedger(events, ids),
                new AdaptationProfileStore());
    }

    public BehaviorApi(BehaviorEventStore events, EchoStore accounts, IDGenerator ids,
                       ExplicitFeedbackStore feedbacks, BehaviorLedger ledger) {
        this(events, accounts, ids, feedbacks, ledger, new AdaptationProfileStore());
    }

    public BehaviorApi(BehaviorEventStore events, EchoStore accounts, IDGenerator ids,
                       ExplicitFeedbackStore feedbacks, BehaviorLedger ledger, AdaptationProfileStore profiles) {
        this.events = events;
        this.accounts = accounts;
        this.ids = ids;
        this.feedbacks = feedbacks;
        this.ledger = ledger;
        this.profiles = profiles;
    }

    public BehaviorLedger ledger() {
        return ledger;
    }

    public void register(Router r) {
        r.add("POST", "/behavior-events/batch", this::ingest);
        r.add("POST", "/me/explicit-feedback", this::submitFeedback);
        r.add("GET", "/me/adaptation-profile", this::profile);
        r.add("DELETE", "/me/adaptation-profile", this::clearProfile);
        r.add("PUT", "/me/recommendation-mode", this::setMode);
    }

    private Object ingest(RequestContext ctx) {
        long accountId = ctx.accountId();
        if (accountId <= 0) {
            throw new ApiException(ApiException.UNAUTHORIZED, "先让我认出你，再记下这一步。", "missing account");
        }
        JsonObject body = ctx.body() == null ? new JsonObject() : ctx.body();
        JsonArray raw = body.has("events") && body.get("events").isJsonArray()
                ? body.getAsJsonArray("events") : new JsonArray();
        List<Map<String, Object>> results = new ArrayList<>();
        int limit = Math.min(raw.size(), MAX_BATCH);
        for (int i = 0; i < limit; i++) {
            JsonElement el = raw.get(i);
            if (el == null || !el.isJsonObject()) {
                results.add(rejected("", BehaviorDictionary.REJECT_UNKNOWN));
                continue;
            }
            results.add(acceptOne(accountId, el.getAsJsonObject()));
        }
        for (int i = limit; i < raw.size(); i++) {
            JsonObject extra = raw.get(i).isJsonObject() ? raw.get(i).getAsJsonObject() : new JsonObject();
            results.add(rejected(Json.getString(extra, "idempotencyKey", ""), BehaviorDictionary.REJECT_UNKNOWN));
        }
        return Map.of("results", results);
    }

    private Object submitFeedback(RequestContext ctx) {
        long accountId = ctx.accountId();
        if (accountId <= 0) {
            throw new ApiException(ApiException.UNAUTHORIZED, "先让我认出你，再记下这一步。", "missing account");
        }
        JsonObject body = ctx.body() == null ? new JsonObject() : ctx.body();
        String scope = Json.requireString(body, "scope");
        String targetType = Json.requireString(body, "targetType");
        String targetId = Json.requireString(body, "targetId");
        String question = Json.requireString(body, "questionCode");
        String answer = Json.requireString(body, "answerCode");
        int version = Json.getInt(body, "answerVersion", 0);
        String surface = Json.requireString(body, "sourceSurface");
        String reason = FeedbackDictionary.reject(scope, targetType, surface, question, answer, version);
        if (reason != null) {
            throw new ApiException(ApiException.BAD_PARAM, "这个选择我这边还认不下来。", reason);
        }
        ExplicitFeedback current = feedbacks.active(accountId, scope, question, targetId);
        if (current != null && current.answerCode.equals(answer) && current.answerVersion == version) {
            Map<String, Object> same = new LinkedHashMap<>();
            same.put("feedbackId", current.feedbackId);
            same.put("status", current.status);
            same.put("supersedesId", current.supersedesId);
            return same;
        }
        ExplicitFeedback row = new ExplicitFeedback();
        row.feedbackId = String.valueOf(ids.nextId());
        row.accountId = accountId;
        row.scope = scope;
        row.targetType = targetType;
        row.targetId = targetId;
        row.questionCode = question;
        row.answerCode = answer;
        row.answerVersion = version;
        row.sourceSurface = surface;
        row.occurredAt = System.currentTimeMillis();
        if (current != null) {
            current.status = ExplicitFeedback.SUPERSEDED;
            feedbacks.put(current);
            row.supersedesId = current.feedbackId;
        }
        feedbacks.put(row);
        ledger.explicitFeedback(accountId, current != null, row.feedbackId);
        if ("less_like_this".equals(question)) {
            ledger.lessLikeThisChanged(accountId, targetId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("feedbackId", row.feedbackId);
        out.put("status", row.status);
        out.put("supersedesId", row.supersedesId);
        return out;
    }

    private Object profile(RequestContext ctx) {
        long accountId = requireAccount(ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recommendationMode", profiles.mode(accountId));
        out.put("uiAdaptation", domain(accountId, BehaviorDictionary.UI));
        out.put("publicRecommendation", domain(accountId, BehaviorDictionary.PUBLIC));
        out.put("privateGeneration", domain(accountId, BehaviorDictionary.PRIVATE));
        return out;
    }

    private Object clearProfile(RequestContext ctx) {
        long accountId = requireAccount(ctx);
        String scope = ctx.query("scope", "");
        if (!Set.of(BehaviorDictionary.UI, BehaviorDictionary.PUBLIC, BehaviorDictionary.PRIVATE).contains(scope)) {
            throw new ApiException(ApiException.BAD_PARAM, "这一块我还不认得。", BehaviorDictionary.REJECT_UNKNOWN);
        }
        profiles.clear(accountId, scope);
        return profile(ctx);
    }

    private Object setMode(RequestContext ctx) {
        long accountId = requireAccount(ctx);
        JsonObject body = ctx.body() == null ? new JsonObject() : ctx.body();
        String mode = Json.requireString(body, "mode");
        if (!AdaptationProfileStore.PERSONALIZED.equals(mode)
                && !AdaptationProfileStore.NON_PERSONALIZED.equals(mode)) {
            throw new ApiException(ApiException.BAD_PARAM, "这一块我还不认得。", BehaviorDictionary.REJECT_UNKNOWN);
        }
        profiles.setMode(accountId, mode);
        return profile(ctx);
    }

    private Map<String, Object> domain(long accountId, String scope) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("enabled", profiles.enabled(accountId, scope));
        return row;
    }

    private long requireAccount(RequestContext ctx) {
        long accountId = ctx.accountId();
        if (accountId <= 0) {
            throw new ApiException(ApiException.UNAUTHORIZED, "先让我认出你，再记下这一步。", "missing account");
        }
        return accountId;
    }

    private Map<String, Object> acceptOne(long accountId, JsonObject raw) {
        String key = Json.getString(raw, "idempotencyKey", "").strip();
        if (key.isBlank()) {
            return rejected("", BehaviorDictionary.REJECT_UNKNOWN);
        }
        BehaviorEvent existing = events.byIdempotency(accountId, key);
        if (existing != null) {
            return result(key, "duplicate", existing.eventId, null);
        }
        String reason = validate(raw);
        if (reason != null) {
            return rejected(key, reason);
        }
        BehaviorEvent event = toEvent(accountId, raw);
        BehaviorEvent stored = events.putIfAbsent(event);
        if (stored != event) {
            return result(key, "duplicate", stored.eventId, null);
        }
        return result(key, "accepted", event.eventId, null);
    }

    private String validate(JsonObject raw) {
        if (Json.getInt(raw, "schemaVersion", -1) != BehaviorDictionary.SCHEMA_VERSION) {
            return BehaviorDictionary.REJECT_UNKNOWN;
        }
        if (Json.getString(raw, "sessionId", "").isBlank()
                || parseOccurredAt(raw) == null
                || negativeDuration(raw)) {
            return BehaviorDictionary.REJECT_CONTEXT_VALUE;
        }
        raw.remove("accountId");
        raw.remove("anonymousState");
        return BehaviorDictionary.rejectClient(raw);
    }

    private BehaviorEvent toEvent(long accountId, JsonObject raw) {
        BehaviorEvent event = new BehaviorEvent();
        event.eventId = String.valueOf(ids.nextId());
        event.accountId = accountId;
        event.anonymousState = guest(accountId) ? BehaviorEvent.ANONYMOUS : BehaviorEvent.BOUND;
        event.sessionId = Json.requireString(raw, "sessionId");
        event.eventName = Json.requireString(raw, "eventName");
        event.surface = Json.requireString(raw, "surface");
        event.targetType = Json.requireString(raw, "targetType");
        event.targetId = Json.getString(raw, "targetId", null);
        event.activeDurationMs = optionalLong(raw, "activeDurationMs");
        event.foregroundDurationMs = optionalLong(raw, "foregroundDurationMs");
        event.loadWaitMs = optionalLong(raw, "loadWaitMs");
        event.attemptCount = optionalInt(raw, "attemptCount");
        event.backtrackCount = optionalInt(raw, "backtrackCount");
        event.contextJson = BehaviorDictionary.contextOf(raw).toString();
        event.occurredAt = parseOccurredAt(raw);
        event.receivedAt = System.currentTimeMillis();
        event.schemaVersion = BehaviorDictionary.SCHEMA_VERSION;
        event.purposeCode = Json.requireString(raw, "purposeCode");
        event.idempotencyKey = Json.requireString(raw, "idempotencyKey");
        return event;
    }

    private boolean guest(long accountId) {
        AccountProfile profile = accounts.profile(accountId);
        return profile == null || profile.guest;
    }

    private static boolean negativeDuration(JsonObject raw) {
        return negative(raw, "activeDurationMs")
                || negative(raw, "foregroundDurationMs")
                || negative(raw, "loadWaitMs");
    }

    private static boolean negative(JsonObject raw, String key) {
        Long v = optionalLong(raw, key);
        return v != null && v < 0;
    }

    private static Long optionalLong(JsonObject raw, String key) {
        if (!raw.has(key) || raw.get(key).isJsonNull()) {
            return null;
        }
        try {
            return raw.get(key).getAsLong();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer optionalInt(JsonObject raw, String key) {
        if (!raw.has(key) || raw.get(key).isJsonNull()) {
            return null;
        }
        try {
            return raw.get(key).getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long parseOccurredAt(JsonObject raw) {
        if (!raw.has("occurredAt") || raw.get("occurredAt").isJsonNull()) {
            return null;
        }
        try {
            if (raw.get("occurredAt").isJsonPrimitive() && raw.get("occurredAt").getAsJsonPrimitive().isNumber()) {
                long ms = raw.get("occurredAt").getAsLong();
                return ms > 0 ? ms : null;
            }
            Instant instant = Instant.parse(raw.get("occurredAt").getAsString());
            return instant.toEpochMilli();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> rejected(String key, String reason) {
        return result(key, "rejected", null, reason);
    }

    private static Map<String, Object> result(String key, String status, String eventId, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("idempotencyKey", key.isBlank() ? null : key);
        row.put("status", status);
        row.put("eventId", eventId);
        row.put("reasonCode", reason);
        return row;
    }
}
