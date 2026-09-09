package com.echo.http.onboarding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Contract projections; domain state remains server authoritative. */
public final class OnboardingViews {
    private OnboardingViews() { }

    public static Map<String, Object> snapshot(OnboardingAggregate s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("onboardingId", s.onboardingId);
        out.put("accountId", String.valueOf(s.accountId));
        out.put("petName", s.petName);
        out.put("status", s.status);
        out.put("currentStep", s.currentStep);
        out.put("sessionVersion", s.sessionVersion);
        out.put("lastOperation", s.lastOperation);
        out.put("allowedActions", allowedActions(s));
        out.put("selectedSubjectId", s.selectedSubjectId);
        out.put("selectedCandidateId", s.selectedCandidateId);
        out.put("generationJob", s.generationJob);
        return out;
    }

    static void replaceSnapshot(Map<String, Object> response, OnboardingAggregate s) {
        if (response.containsKey("snapshot")) {
            response.put("snapshot", snapshot(s));
        }
    }

    public static Map<String, Object> detail(OnboardingAggregate s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot", snapshot(s));
        out.put("assets", s.assets.stream().map(asset -> asset(s, asset)).toList());
        out.put("subjectCandidates", s.subjects);
        OnboardingAggregate.Subject selected = selectedSubject(s);
        out.put("selectedSubject", selected);
        out.put("crop", selected == null ? null : selected.boundingBox);
        out.put("answers", s.answers.values());
        out.put("candidates", s.candidates);
        out.put("memoryUseConsent", consent(s));
        return out;
    }

    public static Map<String, Object> asset(OnboardingAggregate s, OnboardingAggregate.Asset asset) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assetId", asset.assetId);
        out.put("mediaType", asset.mediaType);
        out.put("slotIndex", asset.slotIndex);
        out.put("selectedSubjectId", asset.selectedSubjectId);
        out.put("crop", asset.crop);
        out.put("qualityState", asset.qualityState);
        out.put("qualityReasonCode", asset.qualityReasonCode);
        out.put("identityState", asset.identityState);
        out.put("createdAt", asset.createdAt);
        out.put("url", "/api/v1/pet/onboarding/" + s.onboardingId + "/assets/" + asset.assetId + "/content");
        return out;
    }

    private static OnboardingAggregate.Subject selectedSubject(OnboardingAggregate s) {
        if (s.selectedSubjectId == null) return null;
        return s.subjects.stream()
                .filter(value -> s.selectedSubjectId.equals(value.subjectId))
                .findFirst()
                .orElse(null);
    }

    public static Map<String, Object> consent(OnboardingAggregate s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("granted", s.consent.granted);
        out.put("consentVersion", s.consent.consentVersion);
        out.put("policyVersion", s.consent.policyVersion);
        out.put("grantedAt", s.consent.grantedAt);
        out.put("withdrawnAt", s.consent.withdrawnAt);
        return out;
    }

    private static List<String> allowedActions(OnboardingAggregate s) {
        if ("confirmed".equals(s.status) || "abandoned".equals(s.status)) return List.of();
        List<String> actions = new ArrayList<>();
        if ("collecting".equals(s.status)) {
            actions.addAll(List.of("upload_asset", "select_subject", "save_answer", "abandon"));
        } else if ("ready_to_bind".equals(s.status)) {
            actions.addAll(List.of("bind_phone", "save_answer", "abandon"));
        } else if ("ready_to_generate".equals(s.status)) {
            actions.addAll(List.of("generate", "save_answer", "abandon"));
        } else if ("candidate_ready".equals(s.status)) {
            actions.addAll(List.of("select_candidate", "refine", "abandon"));
        } else if ("ready_to_confirm".equals(s.status)) {
            actions.addAll(List.of("refine", "abandon"));
            if (s.consent.granted) actions.add("confirm");
        }
        return actions;
    }
}
