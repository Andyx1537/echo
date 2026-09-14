package com.echo.http.behavior;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Set;

/**
 * Phase 0 v1 字典。未知名称与未登记键一律拒绝，不静默放宽。
 */
public final class BehaviorDictionary {
    public static final int SCHEMA_VERSION = 1;

    public static final String UI = "ui_adaptation";
    public static final String PUBLIC = "public_recommendation";
    public static final String PRIVATE = "private_generation";

    public static final String REJECT_UNKNOWN = "dictionary_entry_unknown";
    public static final String REJECT_CONTEXT_FIELD = "context_field_not_allowed";
    public static final String REJECT_CONTEXT_VALUE = "context_field_invalid";
    public static final String REJECT_EMITTER = "emitter_not_allowed";

    private static final Set<String> SURFACES = Set.of(
            "private_onboarding", "first_generation", "plaza", "work_detail");
    private static final Set<String> TARGET_TYPES = Set.of(
            "onboarding_session", "asset", "subject", "question",
            "generation_result", "plaza_batch", "work", "author", "comment_panel");
    private static final Set<String> PURPOSES = Set.of(UI, PUBLIC, PRIVATE);

    private static final Set<String> CLIENT_EVENTS = Set.of(
            "onboarding_asset_upload_started",
            "onboarding_subjects_presented",
            "onboarding_subject_selected",
            "onboarding_crop_submitted",
            "onboarding_question_viewed",
            "onboarding_question_answered",
            "onboarding_question_changed",
            "onboarding_question_skipped",
            "onboarding_backtracked",
            "onboarding_summary_viewed",
            "onboarding_bind_prompt_shown",
            "onboarding_candidate_selected",
            "onboarding_refine_requested",
            "generation_result_viewed",
            "generation_playback_completed",
            "generation_result_replayed",
            "plaza_batch_received",
            "work_impression",
            "work_opened",
            "work_effective_view",
            "work_playback_completed",
            "work_replayed",
            "comment_panel_opened");

    private static final Set<String> SERVER_EVENTS = Set.of(
            "onboarding_session_started",
            "onboarding_asset_upload_succeeded",
            "onboarding_asset_upload_failed",
            "onboarding_asset_quality_failed",
            "onboarding_asset_quality_accepted",
            "onboarding_binding_completed",
            "onboarding_generation_requested",
            "onboarding_generation_succeeded",
            "onboarding_generation_failed",
            "onboarding_confirmed",
            "onboarding_abandoned",
            "explicit_feedback_submitted",
            "explicit_feedback_changed",
            "work_like_changed",
            "work_favorite_changed",
            "author_follow_changed",
            "less_like_this_changed");

    private static final Set<String> ONBOARDING_KEYS = Set.of(
            "questionId", "questionVersion", "answerCodes",
            "mediaType", "slotIndex", "qualityReasonCode", "attemptIndex");
    private static final Set<String> GENERATION_KEYS = Set.of(
            "candidateIndex", "mediumCode", "playbackPercentBucket");
    private static final Set<String> PLAZA_KEYS = Set.of(
            "batchId", "position", "sourceSurface", "mediaFormat", "effectiveDurationBucket");

    private static final Set<String> FORBIDDEN = Set.of(
            "phone", "mobile", "sms", "code", "verifycode", "verificationcode",
            "body", "text", "freetext", "content", "raw", "mediabody",
            "imagebase64", "caption");

    private BehaviorDictionary() {
    }

    public static boolean clientEvent(String name) {
        return CLIENT_EVENTS.contains(name);
    }

    public static boolean serverEvent(String name) {
        return SERVER_EVENTS.contains(name);
    }

    public static String rejectClient(JsonObject raw) {
        String name = text(raw, "eventName");
        if (name == null || (!clientEvent(name) && !serverEvent(name))) {
            return REJECT_UNKNOWN;
        }
        if (serverEvent(name)) {
            return REJECT_EMITTER;
        }
        if (!SURFACES.contains(text(raw, "surface"))
                || !TARGET_TYPES.contains(text(raw, "targetType"))
                || !PURPOSES.contains(text(raw, "purposeCode"))) {
            return REJECT_UNKNOWN;
        }
        JsonObject context = contextOf(raw);
        String field = firstForbidden(context);
        if (field != null) {
            return REJECT_CONTEXT_FIELD;
        }
        Set<String> allowed = allowedKeys(name);
        for (String key : context.keySet()) {
            if (!allowed.contains(key)) {
                return REJECT_CONTEXT_FIELD;
            }
        }
        return firstInvalidValue(name, context);
    }

    public static JsonObject contextOf(JsonObject raw) {
        if (raw.has("context") && raw.get("context").isJsonObject()) {
            return raw.getAsJsonObject("context");
        }
        return new JsonObject();
    }

    public static Set<String> allowedKeys(String eventName) {
        if (eventName.startsWith("onboarding_")) {
            return ONBOARDING_KEYS;
        }
        if (eventName.startsWith("generation_")) {
            return GENERATION_KEYS;
        }
        return PLAZA_KEYS;
    }

    private static String firstForbidden(JsonObject context) {
        for (String key : context.keySet()) {
            if (FORBIDDEN.contains(key.toLowerCase(Locale.ROOT))) {
                return key;
            }
        }
        return null;
    }

    private static String firstInvalidValue(String eventName, JsonObject context) {
        if (needsQuestion(eventName)) {
            if (!isString(context, "questionId") || !isInt(context, "questionVersion")) {
                return REJECT_CONTEXT_VALUE;
            }
        }
        if (needsAnswers(eventName) && !isStringArray(context, "answerCodes")) {
            return REJECT_CONTEXT_VALUE;
        }
        if (needsMedia(eventName)) {
            String media = text(context, "mediaType");
            if (!Set.of("image", "video").contains(media) || !isInt(context, "slotIndex")) {
                return REJECT_CONTEXT_VALUE;
            }
        }
        if (eventName.equals("onboarding_asset_quality_failed")
                && !isString(context, "qualityReasonCode")) {
            return REJECT_CONTEXT_VALUE;
        }
        if (needsPlayback(eventName)) {
            String medium = text(context, "mediumCode");
            if (!Set.of("still", "four_panel", "video").contains(medium)) {
                return REJECT_CONTEXT_VALUE;
            }
            if (!Set.of(0, 25, 50, 75, 100).contains(intOrNull(context, "playbackPercentBucket"))) {
                return REJECT_CONTEXT_VALUE;
            }
        }
        if (needsPlazaBrowse(eventName)) {
            if (!isString(context, "batchId") || !isInt(context, "position")
                    || !isString(context, "sourceSurface")
                    || !Set.of("still", "comic", "video").contains(text(context, "mediaFormat"))) {
                return REJECT_CONTEXT_VALUE;
            }
            if (context.has("effectiveDurationBucket")) {
                String bucket = text(context, "effectiveDurationBucket");
                if (!Set.of("short", "medium", "long").contains(bucket)) {
                    return REJECT_CONTEXT_VALUE;
                }
            }
        }
        if (context.has("attemptIndex") && !isInt(context, "attemptIndex")) {
            return REJECT_CONTEXT_VALUE;
        }
        if (context.has("candidateIndex") && !isInt(context, "candidateIndex")) {
            return REJECT_CONTEXT_VALUE;
        }
        return null;
    }

    private static boolean needsQuestion(String name) {
        return name.contains("question_");
    }

    private static boolean needsAnswers(String name) {
        return name.equals("onboarding_question_answered") || name.equals("onboarding_question_changed");
    }

    private static boolean needsMedia(String name) {
        return name.contains("asset_") || name.equals("onboarding_crop_submitted");
    }

    private static boolean needsPlayback(String name) {
        return name.equals("generation_playback_completed") || name.equals("generation_result_replayed");
    }

    private static boolean needsPlazaBrowse(String name) {
        return name.equals("work_impression") || name.equals("work_opened")
                || name.equals("work_effective_view") || name.equals("work_playback_completed")
                || name.equals("work_replayed");
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static boolean isString(JsonObject o, String key) {
        String v = text(o, key);
        return v != null && !v.isBlank();
    }

    private static boolean isInt(JsonObject o, String key) {
        return intOrNull(o, key) != null;
    }

    private static Integer intOrNull(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive() || !o.get(key).getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isStringArray(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return false;
        }
        JsonArray arr = o.getAsJsonArray(key);
        if (arr.size() == 0) {
            return false;
        }
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()
                    || el.getAsString().isBlank()) {
                return false;
            }
        }
        return true;
    }

}
