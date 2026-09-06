package com.echo.http.onboarding;

import com.echo.http.ApiException;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Test/dev repository with the same CAS and idempotency semantics as PostgreSQL. */
public final class InMemoryOnboardingRepository implements OnboardingRepository {
    private static final Gson GSON = new Gson();
    private final Map<String, OnboardingAggregate> sessions = new ConcurrentHashMap<>();
    private final Map<String, Replay> replays = new ConcurrentHashMap<>();

    @Override
    public OnboardingAggregate create(OnboardingAggregate aggregate) {
        OnboardingAggregate existing = sessions.putIfAbsent(aggregate.onboardingId, copy(aggregate));
        if (existing != null) {
            if (java.util.Objects.equals(existing.createRequestHash, aggregate.createRequestHash)) return copy(existing);
            throw conflict("idempotency_conflict", null);
        }
        return copy(aggregate);
    }

    @Override
    public OnboardingAggregate find(String onboardingId) {
        OnboardingAggregate value = sessions.get(onboardingId);
        return value == null ? null : copy(value);
    }

    @Override
    public Map<String, Object> replay(String onboardingId, String key, String hash) {
        Replay replay = replays.get(onboardingId + ":" + key);
        if (replay == null) return null;
        if (!replay.hash.equals(hash)) throw conflict("idempotency_conflict", null);
        return copyMap(replay.response);
    }

    @Override
    public Map<String, Object> mutate(String id, long accountId, long expectedVersion, String key,
                                      String hash, Mutation mutation) {
        synchronized (sessions) {
            String replayKey = id + ":" + key;
            Replay replay = replays.get(replayKey);
            if (replay != null) {
                if (!replay.hash.equals(hash)) {
                    throw conflict("idempotency_conflict", null);
                }
                return copyMap(replay.response);
            }
            OnboardingAggregate current = sessions.get(id);
            if (current == null) {
                throw new ApiException(ApiException.NOT_FOUND, "这段建档进度没有找到。", "onboarding_not_found");
            }
            if (current.accountId != accountId) {
                throw new ApiException(ApiException.RULE_FORBIDDEN, "这不是你的建档进度。", "onboarding_forbidden");
            }
            if (current.sessionVersion != expectedVersion) {
                throw conflict("onboarding_version_conflict", Map.of("currentSnapshot", OnboardingViews.snapshot(current)));
            }
            OnboardingAggregate next = copy(current);
            Map<String, Object> response;
            try {
                response = mutation.apply(next, null);
            } catch (java.sql.SQLException e) {
                throw new ApiException(ApiException.SERVER_ERROR,
                        "建档进度暂时没能保存，请稍后再试。", "onboarding_storage_error");
            }
            next.sessionVersion++;
            next.updatedAt = System.currentTimeMillis();
            OnboardingViews.replaceSnapshot(response, next);
            sessions.put(id, copy(next));
            replays.put(replayKey, new Replay(hash, copyMap(response)));
            return response;
        }
    }

    @Override
    public boolean mutateSystem(String id, Predicate<OnboardingAggregate> mutation) {
        synchronized (sessions) {
            OnboardingAggregate current = sessions.get(id);
            if (current == null) return false;
            OnboardingAggregate next = copy(current);
            if (!mutation.test(next)) return false;
            next.sessionVersion++;
            next.updatedAt = System.currentTimeMillis();
            sessions.put(id, copy(next));
            return true;
        }
    }

    private static ApiException conflict(String detail, Map<String, Object> data) {
        return new ApiException(ApiException.RULE_FORBIDDEN, "建档状态刚刚发生了变化，请刷新后继续。", detail, data);
    }

    private static OnboardingAggregate copy(OnboardingAggregate value) {
        return GSON.fromJson(GSON.toJson(value), OnboardingAggregate.class);
    }

    private static Map<String, Object> copyMap(Map<String, Object> value) {
        return GSON.fromJson(GSON.toJson(value), new TypeToken<LinkedHashMap<String, Object>>() { }.getType());
    }

    private record Replay(String hash, Map<String, Object> response) { }
}
