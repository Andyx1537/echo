package com.echo.http.onboarding;

import com.echo.http.ApiException;
import com.echo.infra.persistence.PgDb;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** PostgreSQL aggregate repository. CAS, projections and idempotency commit together. */
public final class PgOnboardingRepository implements OnboardingRepository {
    private static final Gson GSON = new Gson();
    private final PgDb db;

    public PgOnboardingRepository(PgDb db) {
        this.db = db;
    }

    @Override
    public OnboardingAggregate create(OnboardingAggregate aggregate) {
        try {
            return db.inTransaction(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO \"t_onboarding_session\"(\"onboardingId\",\"accountId\",\"status\","
                                + "\"currentStep\",\"sessionVersion\",\"payload\",\"createdAt\",\"updatedAt\") "
                                + "VALUES(?,?,?,?,?,?,?,?)")) {
                    bindSession(ps, aggregate);
                    ps.executeUpdate();
                }
                syncChildren(c, aggregate);
                return copy(aggregate);
            });
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                OnboardingAggregate existing = find(aggregate.onboardingId);
                if (existing != null && java.util.Objects.equals(existing.createRequestHash, aggregate.createRequestHash)) {
                    return existing;
                }
                throw conflict("idempotency_conflict", null);
            }
            throw storage(e);
        }
    }

    @Override
    public OnboardingAggregate find(String id) {
        try (Connection c = db.getConnection()) {
            return load(c, id, false);
        } catch (SQLException e) {
            throw storage(e);
        }
    }

    @Override
    public Map<String, Object> replay(String id, String key, String hash) {
        try (Connection c = db.getConnection()) {
            Replay replay = replay(c, id, key);
            if (replay == null) return null;
            if (!replay.requestHash.equals(hash)) throw conflict("idempotency_conflict", null);
            return map(replay.responseJson);
        } catch (ApiException e) {
            throw e;
        } catch (SQLException e) {
            throw storage(e);
        }
    }

    @Override
    public Map<String, Object> mutate(String id, long accountId, long expectedVersion, String key,
                                      String hash, Mutation mutation) {
        try {
            return db.inTransaction(c -> {
                Replay replay = replay(c, id, key);
                if (replay != null) {
                    if (!replay.requestHash.equals(hash)) throw conflict("idempotency_conflict", null);
                    return map(replay.responseJson);
                }
                OnboardingAggregate current = requireOwned(c, id, accountId, true);
                if (current.sessionVersion != expectedVersion) {
                    throw conflict("onboarding_version_conflict",
                            Map.of("currentSnapshot", OnboardingViews.snapshot(current)));
                }
                Map<String, Object> response = mutation.apply(current, c);
                current.sessionVersion++;
                current.updatedAt = System.currentTimeMillis();
                OnboardingViews.replaceSnapshot(response, current);
                update(c, current, expectedVersion);
                syncChildren(c, current);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO \"t_onboarding_idempotency\"(\"onboardingId\",\"idempotencyKey\","
                                + "\"requestHash\",\"responseJson\",\"createdAt\") VALUES(?,?,?,?,?)")) {
                    ps.setString(1, id);
                    ps.setString(2, key);
                    ps.setString(3, hash);
                    ps.setString(4, GSON.toJson(response));
                    ps.setLong(5, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                return response;
            });
        } catch (ApiException e) {
            throw e;
        } catch (SQLException e) {
            throw storage(e);
        }
    }

    @Override
    public boolean mutateSystem(String id, Predicate<OnboardingAggregate> mutation) {
        try {
            return db.inTransaction(c -> {
                OnboardingAggregate current = load(c, id, true);
                if (current == null) return false;
                long expected = current.sessionVersion;
                if (!mutation.test(current)) return false;
                current.sessionVersion++;
                current.updatedAt = System.currentTimeMillis();
                update(c, current, expected);
                syncChildren(c, current);
                return true;
            });
        } catch (SQLException e) {
            throw storage(e);
        }
    }

    private OnboardingAggregate requireOwned(Connection c, String id, long accountId, boolean lock) throws SQLException {
        OnboardingAggregate value = load(c, id, lock);
        if (value == null) throw new ApiException(ApiException.NOT_FOUND, "这段建档进度没有找到。", "onboarding_not_found");
        if (value.accountId != accountId) throw new ApiException(ApiException.RULE_FORBIDDEN, "这不是你的建档进度。", "onboarding_forbidden");
        return value;
    }

    private OnboardingAggregate load(Connection c, String id, boolean lock) throws SQLException {
        String sql = "SELECT \"payload\" FROM \"t_onboarding_session\" WHERE \"onboardingId\"=?"
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? GSON.fromJson(rs.getString(1), OnboardingAggregate.class) : null;
            }
        }
    }

    private void update(Connection c, OnboardingAggregate s, long expected) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE \"t_onboarding_session\" SET \"status\"=?,\"currentStep\"=?,"
                        + "\"sessionVersion\"=?,\"payload\"=?,\"updatedAt\"=? WHERE \"onboardingId\"=? "
                        + "AND \"sessionVersion\"=?")) {
            ps.setString(1, s.status);
            ps.setString(2, s.currentStep);
            ps.setLong(3, s.sessionVersion);
            ps.setString(4, GSON.toJson(s));
            ps.setLong(5, s.updatedAt);
            ps.setString(6, s.onboardingId);
            ps.setLong(7, expected);
            if (ps.executeUpdate() != 1) throw conflict("onboarding_version_conflict", null);
        }
    }

    private static void bindSession(PreparedStatement ps, OnboardingAggregate s) throws SQLException {
        ps.setString(1, s.onboardingId);
        ps.setLong(2, s.accountId);
        ps.setString(3, s.status);
        ps.setString(4, s.currentStep);
        ps.setLong(5, s.sessionVersion);
        ps.setString(6, GSON.toJson(s));
        ps.setLong(7, s.createdAt);
        ps.setLong(8, s.updatedAt);
    }

    private static void syncChildren(Connection c, OnboardingAggregate s) throws SQLException {
        replace(c, "t_onboarding_subject", s.onboardingId, GSON.toJson(s.subjects));
        replace(c, "t_onboarding_asset", s.onboardingId, GSON.toJson(s.assets));
        replace(c, "t_onboarding_answer", s.onboardingId,
                GSON.toJson(Map.of("current", s.answers.values(), "history", s.answerHistory)));
        replace(c, "t_pet_profile_fact", s.onboardingId, GSON.toJson(s.facts));
        replace(c, "t_generation_anchor", s.onboardingId, GSON.toJson(s.anchors));
    }

    private static void replace(Connection c, String table, String id, String payload) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO \"" + table
                + "\"(\"onboardingId\",\"payload\",\"updatedAt\") VALUES(?,?,?) ON CONFLICT "
                + "(\"onboardingId\") DO UPDATE SET \"payload\"=EXCLUDED.\"payload\","
                + "\"updatedAt\"=EXCLUDED.\"updatedAt\"")) {
            ps.setString(1, id);
            ps.setString(2, payload);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private static Replay replay(Connection c, String id, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT \"requestHash\",\"responseJson\" FROM \"t_onboarding_idempotency\" "
                        + "WHERE \"onboardingId\"=? AND \"idempotencyKey\"=?")) {
            ps.setString(1, id);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Replay(rs.getString(1), rs.getString(2)) : null;
            }
        }
    }

    private static ApiException conflict(String detail, Map<String, Object> data) {
        return new ApiException(ApiException.RULE_FORBIDDEN, "建档状态刚刚发生了变化，请刷新后继续。", detail, data);
    }

    private static ApiException storage(SQLException e) {
        return new ApiException(ApiException.SERVER_ERROR, "建档进度暂时没能保存，请稍后再试。", "onboarding_storage_error");
    }

    private static OnboardingAggregate copy(OnboardingAggregate s) {
        return GSON.fromJson(GSON.toJson(s), OnboardingAggregate.class);
    }

    private static Map<String, Object> map(String json) {
        return GSON.fromJson(json, new TypeToken<LinkedHashMap<String, Object>>() { }.getType());
    }

    private record Replay(String requestHash, String responseJson) { }
}
