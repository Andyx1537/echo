package com.echo.http.onboarding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable aggregate for the private, single-pet onboarding flow. */
public final class OnboardingAggregate {
    public String onboardingId;
    public long accountId;
    public String flowVersion;
    public String questionnaireVersion;
    public String petName = "它";
    public String createRequestHash;
    public String status = "collecting";
    public String currentStep = "upload";
    public long sessionVersion;
    public String lastOperation = "none";
    public String selectedSubjectId;
    public String selectedCandidateId;
    public Consent consent = new Consent();
    public Job generationJob;
    public String confirmedPetId;
    public String confirmedWindowId;
    public long createdAt;
    public long updatedAt;
    public List<Subject> subjects = new ArrayList<>();
    public List<Asset> assets = new ArrayList<>();
    public Map<String, Answer> answers = new LinkedHashMap<>();
    public List<Answer> answerHistory = new ArrayList<>();
    public List<Fact> facts = new ArrayList<>();
    public List<Anchor> anchors = new ArrayList<>();
    public List<Candidate> candidates = new ArrayList<>();

    public static final class Subject {
        public String subjectId;
        public String assetId;
        public String modelType;
        public String species;
        public double confidence;
        public Crop boundingBox;
        public boolean userSelected;
        public String identityClusterId;
    }

    public static final class Asset {
        public String assetId;
        public String resourceId;
        public String mediaType;
        public int slotIndex;
        public String selectedSubjectId;
        public Crop crop;
        public String qualityState;
        public String qualityReasonCode;
        public String identityState;
        public long createdAt;
    }

    public static final class Crop {
        public double x;
        public double y;
        public double w;
        public double h;
    }

    public static final class Answer {
        public String answerId;
        public String questionId;
        public String answerVersion;
        public List<String> answerCodes = new ArrayList<>();
        public String freeText;
        public String freeTextSource;
        public String supersedesId;
        public long answeredAt;
        public Long replacedAt;
    }

    public static final class Fact {
        public String factId;
        public String dimension;
        public String value;
        public String sourceType;
        public String sourceRefId;
        public double confidence;
        public String visibility;
        public List<String> allowedUses = new ArrayList<>();
        public long validFrom;
        public Long supersededAt;
    }

    public static final class Anchor {
        public String anchorId;
        public long sessionVersion;
        public String subjectSnapshot;
        public String assetSnapshot;
        public String answerSnapshot;
        public String factSnapshot;
        public String promptTemplateVersion;
        public String safetyDecision;
        public long createdAt;
    }

    public static final class Candidate {
        public String candidateId;
        public String gradient;
        public String emoji;
        public String signature;
    }

    public static final class Consent {
        public boolean granted;
        public long consentVersion;
        public String policyVersion;
        public Long grantedAt;
        public Long withdrawnAt;
    }

    public static final class Job {
        public String jobId;
        public String status;
        public long pollAfterMs = 1500;
    }
}
