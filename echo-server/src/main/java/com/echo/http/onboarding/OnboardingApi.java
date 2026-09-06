package com.echo.http.onboarding;

import com.aengine.util.id.IDGenerator;
import com.echo.http.ApiException;
import com.echo.http.HttpResult;
import com.echo.http.RequestContext;
import com.echo.http.Router;
import com.echo.infra.vision.DetectResult;
import com.echo.infra.vision.DetectSubject;
import com.echo.infra.vision.IVisionClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** HTTP application service for the frozen private onboarding slice. */
public final class OnboardingApi {
    private static final Gson GSON = new Gson();
    private static final Map<String, Set<String>> OPTIONS = Map.of(
            "Q1", Set.of("home", "adoption", "family_friend", "outdoors", "clinic_rescue", "online", "unclear"),
            "Q2", Set.of("tiny", "timid", "quiet", "eye_contact", "approached_quickly", "exploring", "tired", "energetic", "same_as_now", "appearance_unclear"),
            "Q3", Set.of("follows_me", "waits_for_me", "nuzzles", "sleeps_in_spot", "watches_window", "runs_to_sound", "plays_together", "stays_quietly", "food_motivated", "explores", "special_gesture"),
            "Q4", Set.of("waits_at_door", "sleeps_in_familiar_spot", "goes_out_together", "eats_beside_me", "watches_me", "being_petted", "runs_over", "ordinary_routine"));

    private final OnboardingRepository repository;
    private final OnboardingIdentityPort identity;
    private final OnboardingGenerationPort generation;
    private final OnboardingWindowPort windows;
    private final IVisionClient vision;
    private final IDGenerator ids;

    public OnboardingApi(OnboardingRepository repository, OnboardingIdentityPort identity,
                         OnboardingGenerationPort generation, OnboardingWindowPort windows,
                         IVisionClient vision, IDGenerator ids) {
        this.repository = repository;
        this.identity = identity;
        this.generation = generation;
        this.windows = windows;
        this.vision = vision;
        this.ids = ids;
    }

    public void register(Router router) {
        router.add("POST", "/pet/onboarding", this::create);
        router.add("PATCH", "/pet/onboarding/:id/profile", this::profile);
        router.add("POST", "/pet/onboarding/:id/subject/select", this::selectSubject);
        router.add("PUT", "/pet/onboarding/:id/answers/:questionId", this::answer);
        router.add("PUT", "/pet/onboarding/:id/consent", this::consent);
        router.add("GET", "/pet/onboarding/:id", this::get);
        router.add("POST", "/pet/onboarding/:id/generate", this::generate);
        router.add("POST", "/pet/onboarding/:id/candidates/:candidateId/select", this::selectCandidate);
        router.add("POST", "/pet/onboarding/:id/refine", this::refine);
        router.add("POST", "/pet/onboarding/:id/confirm", this::confirm);
        router.add("DELETE", "/pet/onboarding/:id", this::abandon);
    }

    private Object create(RequestContext ctx) {
        String key = requireKey(ctx);
        JsonObject body = ctx.body();
        String requestHash = hash(body);
        String deterministicId = java.util.UUID.nameUUIDFromBytes(
                (ctx.accountId() + ":" + key).getBytes(StandardCharsets.UTF_8)).toString();
        OnboardingAggregate existing = repository.find(deterministicId);
        if (existing != null) {
            if (!requestHash.equals(existing.createRequestHash)) throw error("idempotency_conflict");
            return HttpResult.created(OnboardingViews.snapshot(existing));
        }
        long now = System.currentTimeMillis();
        OnboardingAggregate s = new OnboardingAggregate();
        s.onboardingId = deterministicId;
        s.accountId = ctx.accountId();
        s.flowVersion = optional(body, "flowVersion", "v1");
        s.questionnaireVersion = optional(body, "questionnaireVersion", "v1");
        s.petName = name(optional(body, "petName", "它"));
        s.createRequestHash = requestHash;
        s.createdAt = now;
        s.updatedAt = now;
        return HttpResult.created(OnboardingViews.snapshot(repository.create(s)));
    }

    private Object profile(RequestContext ctx) {
        JsonObject body = ctx.body();
        return mutate(ctx, s -> {
            ensureMutable(s);
            s.petName = name(optional(body, "petName", "它"));
            return response(s);
        });
    }

    private Object selectSubject(RequestContext ctx) {
        JsonObject body = ctx.body();
        return mutate(ctx, s -> {
            ensureState(s, "collecting");
            String subjectId = required(body, "subjectId");
            OnboardingAggregate.Subject selected = s.subjects.stream()
                    .filter(value -> subjectId.equals(value.subjectId)).findFirst()
                    .orElseThrow(() -> error("subject_selection_required"));
            s.subjects.forEach(value -> value.userSelected = value == selected);
            selected.userSelected = true;
            s.selectedSubjectId = subjectId;
            OnboardingAggregate.Crop crop = crop(body.getAsJsonObject("crop"));
            selected.boundingBox = crop;
            for (OnboardingAggregate.Asset asset : s.assets) {
                if (asset.assetId.equals(selected.assetId)) {
                    asset.selectedSubjectId = subjectId;
                    asset.crop = crop;
                    asset.qualityState = validCrop(crop) ? "accepted" : "rejected";
                    asset.qualityReasonCode = validCrop(crop) ? null : "crop_not_distinct";
                    asset.identityState = "user_confirmed";
                }
            }
            if (!validCrop(crop)) throw error("asset_quality_failed");
            s.currentStep = "questionnaire";
            advance(s);
            Map<String, Object> out = response(s);
            out.put("selectedSubject", selected);
            out.put("quality", Map.of("accepted", true));
            return out;
        });
    }

    private Object answer(RequestContext ctx) {
        JsonObject body = ctx.body();
        return mutate(ctx, s -> {
            ensureState(s, "collecting", "ready_to_bind", "ready_to_generate");
            String questionId = ctx.path("questionId").toUpperCase(java.util.Locale.ROOT);
            List<String> codes = strings(body.getAsJsonArray("answerCodes"));
            validateAnswer(questionId, codes, body);
            String answerVersion = optional(body, "answerVersion", s.questionnaireVersion);
            if (!s.questionnaireVersion.equals(answerVersion)) throw error("answer_code_invalid");
            OnboardingAggregate.Answer previous = s.answers.get(questionId);
            OnboardingAggregate.Answer answer = new OnboardingAggregate.Answer();
            answer.answerId = String.valueOf(ids.nextId());
            answer.questionId = questionId;
            answer.answerVersion = answerVersion;
            answer.answerCodes.addAll(codes);
            answer.freeText = optionalNullable(body, "freeText");
            answer.freeTextSource = optionalNullable(body, "freeTextSource");
            answer.supersedesId = previous == null ? null : previous.answerId;
            answer.answeredAt = System.currentTimeMillis();
            if (previous != null) {
                previous.replacedAt = answer.answeredAt;
                s.answerHistory.add(previous);
            }
            s.answers.put(questionId, answer);
            rebuildFacts(s, previous, answer);
            advance(s);
            Map<String, Object> out = response(s);
            out.put("answerId", answer.answerId);
            out.put("supersedesId", answer.supersedesId);
            return out;
        });
    }

    private Object consent(RequestContext ctx) {
        JsonObject body = ctx.body();
        return mutate(ctx, s -> {
            ensureMutable(s);
            boolean granted = body.has("granted") && body.get("granted").getAsBoolean();
            long now = System.currentTimeMillis();
            s.consent.granted = granted;
            s.consent.consentVersion++;
            s.consent.policyVersion = required(body, "policyVersion");
            if (granted) {
                s.consent.grantedAt = now;
                s.consent.withdrawnAt = null;
                if (s.selectedCandidateId != null && "candidate_ready".equals(s.status)) {
                    s.status = "ready_to_confirm";
                    s.currentStep = "confirm";
                }
            } else {
                s.consent.withdrawnAt = now;
                if ("ready_to_confirm".equals(s.status)) {
                    s.status = "candidate_ready";
                    s.currentStep = "candidate_select";
                }
            }
            Map<String, Object> out = response(s);
            out.put("memoryUseConsent", OnboardingViews.consent(s));
            return out;
        });
    }

    private Object get(RequestContext ctx) {
        OnboardingAggregate s = owned(ctx.path("id"), ctx.accountId());
        if ("ready_to_bind".equals(s.status) && identity.phoneBound(ctx.accountId())) {
            repository.mutateSystem(s.onboardingId, value -> {
                if ("ready_to_bind".equals(value.status)) {
                    value.status = "ready_to_generate";
                    value.currentStep = "generate";
                    return true;
                }
                return false;
            });
            s = owned(ctx.path("id"), ctx.accountId());
        }
        return OnboardingViews.detail(s);
    }

    private Object generate(RequestContext ctx) {
        if (!identity.phoneBound(ctx.accountId())) throw error("phone_binding_required");
        JsonObject body = ctx.body();
        String jobId = String.valueOf(ids.nextId());
        OnboardingAggregate.Anchor[] holder = new OnboardingAggregate.Anchor[1];
        Map<String, Object> out = mutate(ctx, s -> {
            if ("ready_to_bind".equals(s.status)) s.status = "ready_to_generate";
            ensureState(s, "ready_to_generate");
            holder[0] = anchor(s);
            s.anchors.add(holder[0]);
            s.status = "generating";
            s.currentStep = "generate";
            s.lastOperation = "none";
            s.generationJob = job(jobId);
            return response(s);
        });
        generation.submit(jobId, holder[0], null, (candidates, failure) -> completeGeneration(ctx.path("id"), jobId, candidates, failure, false));
        return HttpResult.accepted(out);
    }

    private Object selectCandidate(RequestContext ctx) {
        return mutate(ctx, s -> {
            ensureState(s, "candidate_ready", "ready_to_confirm");
            String id = ctx.path("candidateId");
            requireCandidate(s, id);
            s.selectedCandidateId = id;
            s.status = s.consent.granted ? "ready_to_confirm" : "candidate_ready";
            s.currentStep = s.consent.granted ? "confirm" : "candidate_select";
            return response(s);
        });
    }

    private Object refine(RequestContext ctx) {
        JsonObject body = ctx.body();
        String candidateId = required(body, "candidateId");
        String adjustment = optional(body, "adjustmentCode", "keep");
        String jobId = String.valueOf(ids.nextId());
        OnboardingAggregate.Anchor[] holder = new OnboardingAggregate.Anchor[1];
        Map<String, Object> out = mutate(ctx, s -> {
            ensureState(s, "candidate_ready", "ready_to_confirm");
            requireCandidate(s, candidateId);
            holder[0] = anchor(s);
            s.anchors.add(holder[0]);
            s.status = "refining";
            s.currentStep = "refine";
            s.generationJob = job(jobId);
            return response(s);
        });
        generation.submit(jobId, holder[0], adjustment, (candidates, failure) -> completeGeneration(ctx.path("id"), jobId, candidates, failure, true));
        return HttpResult.accepted(out);
    }

    private Object confirm(RequestContext ctx) {
        JsonObject body = ctx.body();
        long expected = requiredLong(body, "expectedSessionVersion");
        return repository.mutate(ctx.path("id"), ctx.accountId(), expected,
                requireKey(ctx), hash(body), (s, transaction) -> {
            ensureState(s, "ready_to_confirm");
            String candidateId = required(body, "candidateId");
            OnboardingAggregate.Candidate candidate = requireCandidate(s, candidateId);
            long consentVersion = requiredLong(body, "consentVersion");
            if (!s.consent.granted) throw error("consent_required");
            if (consentVersion != s.consent.consentVersion) throw error("consent_version_conflict");
            OnboardingWindowPort.Result result = windows.confirm(s, candidate, transaction);
            s.selectedCandidateId = candidateId;
            s.confirmedPetId = result.petId();
            s.confirmedWindowId = result.windowId();
            s.status = "confirmed";
            s.currentStep = "done";
            Map<String, Object> out = response(s);
            out.put("petId", result.petId());
            out.put("windowId", result.windowId());
            return out;
        });
    }

    private Object abandon(RequestContext ctx) {
        return mutate(ctx, s -> {
            ensureMutable(s);
            s.status = "abandoned";
            s.currentStep = "done";
            return response(s);
        });
    }

    /** Called by the multipart gateway after bytes and resource ownership are durably stored. */
    public Map<String, Object> replayUploadedAsset(long accountId, String id, String idempotencyKey,
                                                    String requestFingerprint) {
        owned(id, accountId);
        return repository.replay(id, requireKey(idempotencyKey), requestFingerprint);
    }

    public void assertUploadAllowed(long accountId, String id, long expectedVersion, String mediaType) {
        OnboardingAggregate s = owned(id, accountId);
        if (s.sessionVersion != expectedVersion) {
            throw new ApiException(ApiException.RULE_FORBIDDEN,
                    "建档状态刚刚发生了变化，请刷新后继续。", "onboarding_version_conflict",
                    Map.of("currentSnapshot", OnboardingViews.snapshot(s)));
        }
        ensureState(s, "collecting");
        String normalized = normalizeMediaType(mediaType);
        long count = s.assets.stream().filter(asset -> normalized.equals(asset.mediaType)).count();
        if (count >= 2) throw error("asset_limit_exceeded");
    }

    public Map<String, Object> attachUploadedAsset(long accountId, String id, String idempotencyKey,
                                                    long expectedVersion, String resourceId, String mediaType,
                                                    String requestFingerprint) {
        return repository.mutate(id, accountId, expectedVersion, requireKey(idempotencyKey), requestFingerprint, s -> {
            ensureState(s, "collecting");
            String normalizedMediaType = normalizeMediaType(mediaType);
            long count = s.assets.stream().filter(a -> normalizedMediaType.equals(a.mediaType)).count();
            long limit = "image".equals(normalizedMediaType) ? 2 : "video".equals(normalizedMediaType) ? 2 : 0;
            if (limit == 0) throw error("asset_upload_incomplete");
            if (count >= limit) throw error("asset_limit_exceeded");
            OnboardingAggregate.Asset asset = new OnboardingAggregate.Asset();
            asset.assetId = String.valueOf(ids.nextId());
            asset.resourceId = resourceId;
            asset.mediaType = normalizedMediaType;
            asset.slotIndex = (int) count;
            asset.qualityState = "pending_subject_selection";
            asset.identityState = "pending";
            asset.createdAt = System.currentTimeMillis();
            s.assets.add(asset);
            DetectResult detected = vision.detectWithSource(resourceId);
            for (DetectSubject value : detected.subjects()) {
                if (value.subjectType() != DetectSubject.SubjectType.ANIMAL) continue;
                OnboardingAggregate.Subject subject = new OnboardingAggregate.Subject();
                subject.subjectId = String.valueOf(ids.nextId());
                subject.assetId = asset.assetId;
                subject.modelType = value.subjectType().wire();
                subject.species = value.species();
                subject.confidence = value.confidence();
                if (value.box() != null) {
                    subject.boundingBox = new OnboardingAggregate.Crop();
                    subject.boundingBox.x = value.box().x(); subject.boundingBox.y = value.box().y();
                    subject.boundingBox.w = value.box().w(); subject.boundingBox.h = value.box().h();
                }
                subject.identityClusterId = subject.subjectId;
                s.subjects.add(subject);
            }
            if (s.subjects.stream().noneMatch(subject -> asset.assetId.equals(subject.assetId))) {
                throw error("asset_quality_failed");
            }
            s.currentStep = "subject_select";
            Map<String, Object> out = response(s);
            out.put("asset", OnboardingViews.asset(s, asset));
            out.put("subjectCandidates", s.subjects);
            out.put("quality", Map.of("state", asset.qualityState, "source", detected.source().wire()));
            return out;
        });
    }

    public static String uploadFingerprint(String id, long expectedVersion, String mediaType, byte[] data) {
        String normalized = normalizeMediaType(mediaType);
        return hash(id + ":" + expectedVersion + ":" + normalized + ":" + hash(data));
    }

    /** Resolve an asset only after checking that both the session and asset belong to the caller. */
    public String ownedAssetResource(long accountId, String onboardingId, String assetId) {
        OnboardingAggregate s = owned(onboardingId, accountId);
        return s.assets.stream()
                .filter(asset -> assetId.equals(asset.assetId))
                .map(asset -> asset.resourceId)
                .findFirst()
                .orElseThrow(() -> error("onboarding_not_found"));
    }

    private Map<String, Object> mutate(RequestContext ctx, java.util.function.Function<OnboardingAggregate, Map<String, Object>> action) {
        JsonObject body = ctx.body();
        long expected = requiredLong(body, "expectedSessionVersion");
        return repository.mutate(ctx.path("id"), ctx.accountId(), expected, requireKey(ctx), hash(body), action);
    }

    private void completeGeneration(String id, String jobId, List<OnboardingAggregate.Candidate> candidates,
                                    Throwable failure, boolean refine) {
        repository.mutateSystem(id, s -> {
            if (s.generationJob == null || !jobId.equals(s.generationJob.jobId)) return false;
            if (failure != null) {
                s.generationJob.status = "failed";
                s.lastOperation = refine ? "refine_failed" : "generate_failed";
                s.status = refine ? "candidate_ready" : "ready_to_generate";
                s.currentStep = refine ? "candidate_select" : "generate";
                return true;
            }
            s.candidates.clear();
            s.candidates.addAll(candidates);
            s.generationJob.status = "succeeded";
            s.lastOperation = "none";
            s.status = "candidate_ready";
            s.currentStep = "candidate_select";
            s.selectedCandidateId = null;
            return true;
        });
    }

    private OnboardingAggregate owned(String id, long accountId) {
        OnboardingAggregate s = repository.find(id);
        if (s == null) throw error("onboarding_not_found");
        if (s.accountId != accountId) throw error("onboarding_forbidden");
        return s;
    }

    private static Map<String, Object> response(OnboardingAggregate s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot", OnboardingViews.snapshot(s));
        return out;
    }

    private static void advance(OnboardingAggregate s) {
        boolean hasImage = s.assets.stream().anyMatch(a -> "image".equals(a.mediaType) && "accepted".equals(a.qualityState));
        if (hasImage && s.selectedSubjectId != null && s.answers.keySet().containsAll(Set.of("Q1", "Q2", "Q3", "Q4"))) {
            if ("collecting".equals(s.status)) {
                s.status = "ready_to_bind";
                s.currentStep = "bind";
            }
        }
    }

    private void rebuildFacts(OnboardingAggregate s, OnboardingAggregate.Answer previous,
                              OnboardingAggregate.Answer answer) {
        if (previous != null) {
            for (OnboardingAggregate.Fact fact : s.facts) {
                if (previous.answerId.equals(fact.sourceRefId) && fact.supersededAt == null) {
                    fact.supersededAt = answer.answeredAt;
                }
            }
        }
        for (String code : answer.answerCodes) {
            OnboardingAggregate.Fact fact = new OnboardingAggregate.Fact();
            fact.factId = String.valueOf(ids.nextId());
            fact.dimension = switch (answer.questionId) {
                case "Q1" -> "place"; case "Q2" -> "appearance";
                case "Q3" -> "routine"; default -> "generation_preference";
            };
            fact.value = code;
            fact.sourceType = "user_explicit";
            fact.sourceRefId = answer.answerId;
            fact.confidence = 1.0;
            fact.visibility = "private";
            fact.allowedUses.add("private_generation");
            fact.validFrom = answer.answeredAt;
            s.facts.add(fact);
        }
    }

    private OnboardingAggregate.Anchor anchor(OnboardingAggregate s) {
        OnboardingAggregate.Anchor a = new OnboardingAggregate.Anchor();
        a.anchorId = String.valueOf(ids.nextId());
        a.sessionVersion = s.sessionVersion;
        a.subjectSnapshot = GSON.toJson(s.subjects);
        a.assetSnapshot = GSON.toJson(s.assets);
        a.answerSnapshot = GSON.toJson(s.answers.values());
        a.factSnapshot = GSON.toJson(s.facts.stream().filter(fact -> fact.supersededAt == null).toList());
        a.promptTemplateVersion = "private-onboarding-v1";
        a.safetyDecision = "pending_provider_check";
        a.createdAt = System.currentTimeMillis();
        return a;
    }

    private static OnboardingAggregate.Job job(String id) {
        OnboardingAggregate.Job job = new OnboardingAggregate.Job();
        job.jobId = id;
        job.status = "queued";
        return job;
    }

    private static void validateAnswer(String question, List<String> codes, JsonObject body) {
        Set<String> allowed = OPTIONS.get(question);
        if (allowed == null || codes.stream().anyMatch(code -> !allowed.contains(code))) throw error("answer_code_invalid");
        int max = "Q1".equals(question) ? 1 : "Q4".equals(question) ? 2 : 3;
        if (codes.isEmpty() || codes.size() > max || new LinkedHashSet<>(codes).size() != codes.size()) {
            throw error("answer_cardinality_invalid");
        }
        String freeText = optionalNullable(body, "freeText");
        if (freeText != null && !("Q3".equals(question) && codes.contains("special_gesture"))) throw error("answer_code_invalid");
        String source = optionalNullable(body, "freeTextSource");
        if (source != null && !Set.of("typed", "voice_transcript").contains(source)) throw error("answer_code_invalid");
        if (freeText != null && freeText.codePointCount(0, freeText.length()) > 200) throw error("answer_code_invalid");
        if ((freeText == null) != (source == null)) throw error("answer_code_invalid");
    }

    private static List<String> strings(JsonArray array) {
        if (array == null) throw error("answer_cardinality_invalid");
        List<String> out = new ArrayList<>();
        for (JsonElement value : array) out.add(value.getAsString());
        return out;
    }

    private static OnboardingAggregate.Candidate requireCandidate(OnboardingAggregate s, String id) {
        return s.candidates.stream().filter(value -> id.equals(value.candidateId)).findFirst()
                .orElseThrow(() -> error("candidate_not_found"));
    }

    private static OnboardingAggregate.Crop crop(JsonObject value) {
        if (value == null) throw error("asset_quality_failed");
        OnboardingAggregate.Crop c = new OnboardingAggregate.Crop();
        c.x = number(value, "x"); c.y = number(value, "y"); c.w = number(value, "w"); c.h = number(value, "h");
        return c;
    }

    private static boolean validCrop(OnboardingAggregate.Crop c) {
        return c.x >= 0 && c.y >= 0 && c.w > 0.1 && c.h > 0.1 && c.x + c.w <= 1.0 && c.y + c.h <= 1.0;
    }

    private static double number(JsonObject body, String key) {
        if (!body.has(key)) throw error("asset_quality_failed");
        return body.get(key).getAsDouble();
    }

    private static void ensureMutable(OnboardingAggregate s) {
        if ("confirmed".equals(s.status) || "abandoned".equals(s.status)) throw error("onboarding_invalid_state");
    }

    private static void ensureState(OnboardingAggregate s, String... allowed) {
        for (String state : allowed) if (state.equals(s.status)) return;
        throw error("onboarding_invalid_state");
    }

    private static String requireKey(RequestContext ctx) { return requireKey(ctx.header("Idempotency-Key")); }
    private static String requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128) throw error("idempotency_conflict");
        return value;
    }

    private static String required(JsonObject body, String key) {
        if (!body.has(key) || body.get(key).isJsonNull() || body.get(key).getAsString().isBlank()) throw error("onboarding_invalid_state");
        return body.get(key).getAsString();
    }

    private static long requiredLong(JsonObject body, String key) {
        if (!body.has(key)) throw error("onboarding_version_conflict");
        return body.get(key).getAsLong();
    }

    private static String optional(JsonObject body, String key, String fallback) {
        String value = optionalNullable(body, key);
        return value == null ? fallback : value;
    }

    private static String optionalNullable(JsonObject body, String key) {
        return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsString() : null;
    }

    private static String name(String value) {
        String normalized = value == null || value.isBlank() ? "它" : value.trim();
        if (normalized.codePointCount(0, normalized.length()) > 64) throw error("pet_name_invalid");
        return normalized;
    }

    private static String hash(JsonObject body) { return hash(GSON.toJson(body)); }
    private static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException error(String detail) {
        int code = detail.endsWith("not_found") ? ApiException.NOT_FOUND
                : detail.equals("phone_binding_required") ? ApiException.BINDING_REQUIRED : ApiException.RULE_FORBIDDEN;
        return new ApiException(code, "这一步暂时没能完成，请按当前提示继续。", detail);
    }

    private static String normalizeMediaType(String mediaType) {
        String normalized = mediaType == null ? "" : mediaType.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("image", "video").contains(normalized)) throw error("asset_upload_incomplete");
        return normalized;
    }
}
