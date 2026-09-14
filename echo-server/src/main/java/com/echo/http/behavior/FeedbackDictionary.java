package com.echo.http.behavior;

import java.util.Map;
import java.util.Set;

/** Phase 0 明确反馈题目与答案闭集。 */
public final class FeedbackDictionary {
    private static final Set<String> SCOPES = Set.of(
            BehaviorDictionary.UI, BehaviorDictionary.PUBLIC, BehaviorDictionary.PRIVATE);
    private static final Set<String> SURFACES = Set.of(
            "private_onboarding", "first_generation", "plaza", "work_detail");
    private static final Set<String> TARGET_TYPES = Set.of("generation_result", "work");
    private static final Map<String, Set<String>> ANSWERS = Map.of(
            "likeness", Set.of("looks_like_it", "somewhat_like_it", "not_like_it"),
            "ease", Set.of("easy", "acceptable", "difficult"),
            "continue_intent", Set.of("continue", "pause"),
            "change_request", Set.of("keep", "change_scene", "change_style", "change_medium", "regenerate"),
            "less_like_this", Set.of("reduce_similar", "undo_reduce"));

    private FeedbackDictionary() {
    }

    public static String reject(String scope, String targetType, String sourceSurface,
                                String question, String answer, int version) {
        if (!SCOPES.contains(scope) || !SURFACES.contains(sourceSurface) || !TARGET_TYPES.contains(targetType)) {
            return BehaviorDictionary.REJECT_UNKNOWN;
        }
        Set<String> allowed = ANSWERS.get(question);
        if (allowed == null) {
            return BehaviorDictionary.REJECT_UNKNOWN;
        }
        if (!allowed.contains(answer) || version != 1) {
            return BehaviorDictionary.REJECT_CONTEXT_VALUE;
        }
        return null;
    }
}
