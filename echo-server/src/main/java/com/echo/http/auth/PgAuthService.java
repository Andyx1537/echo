package com.echo.http.auth;

import com.aengine.util.id.IDGenerator;
import com.echo.http.ApiException;
import com.echo.http.Json;
import com.echo.infra.persistence.PgDb;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** PostgreSQL authority for phone-account-resolution-v1. */
public final class PgAuthService implements SessionAuthenticator {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private static final Pattern OPAQUE = Pattern.compile("^[A-Za-z0-9_-]{32,256}$");
    private static final long CODE_TTL = 5 * 60_000L;
    private static final long RESOLUTION_TTL = 10 * 60_000L;
    private static final long RESEND_DELAY = 60_000L;

    private final PgDb db;
    private final IDGenerator ids;
    private final AuthCrypto crypto;
    private final SmsProvider sms;
    private final ContinuationPolicy continuations;
    private final Clock clock;

    public PgAuthService(PgDb db, IDGenerator ids, String secret, SmsProvider sms,
                         ContinuationPolicy continuations, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.ids = Objects.requireNonNull(ids);
        this.crypto = new AuthCrypto(secret);
        this.sms = Objects.requireNonNull(sms);
        this.continuations = Objects.requireNonNull(continuations);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public AuthPrincipal authenticate(String token) {
        if (token == null || token.isBlank()) return null;
        String hash = crypto.hash("session", token);
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT \"sessionId\",\"accountId\",\"kind\",\"deviceCredentialId\" "
                        + "FROM \"t_auth_session\" WHERE \"tokenHash\"=? AND \"status\"='active'")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new AuthPrincipal(rs.getLong(2), rs.getString(1), rs.getString(3), rs.getString(4)) : null;
            }
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    public Map<String, Object> deviceSession(String credential, String bootstrapNonce, String idempotencyKey,
                                              String clientIp) {
        requireIdempotency(idempotencyKey, true);
        if (credential == null && (bootstrapNonce == null || !OPAQUE.matcher(bootstrapNonce).matches())) {
            throw bad("device_credential_malformed", "设备凭据格式不正确，请重新进入。", null);
        }
        if (credential != null && !OPAQUE.matcher(credential).matches()) {
            throw bad("device_credential_malformed", "设备凭据格式不正确，请重新进入。", null);
        }
        String actor = crypto.hash("device-actor", credential == null ? bootstrapNonce : credential);
        String requestHash = crypto.hash("request", String.valueOf(credential) + "|" + bootstrapNonce);
        try {
            Map<String, Object> result = db.inTransaction(c -> {
                lock(c, "device-session:" + actor + ":" + idempotencyKey);
                Map<String, Object> replay = replay(c, "device_session", actor, idempotencyKey, requestHash,
                        "device_session_idempotency_conflict");
                if (replay != null) return replay;
                rate(c, "device", actor, 10, 30);
                rate(c, "ip", crypto.hash("ip", safeIp(clientIp)), 20, 100);

                Device found = credential == null ? null : deviceByHash(c, crypto.hash("device", credential), true);
                String action;
                long accountId;
                String credentialId;
                String rawCredential;
                if (found != null && "recovery_only".equals(found.status)) {
                    throw conflict("device_credential_recovery_required", "请从账号切换入口恢复这份资料。", null);
                }
                if (found != null && "login_active".equals(found.status) && isAnonymous(c, found.accountId)) {
                    accountId = found.accountId;
                    credentialId = found.id;
                    rawCredential = credential;
                    action = "restored";
                } else {
                    action = found != null && "revoked".equals(found.status)
                            && "bound".equals(found.reason) ? "rotated_after_bind" : "issued";
                    accountId = createAnonymousAccount(c);
                    rawCredential = AuthCrypto.token(32);
                    credentialId = id();
                    insertDevice(c, credentialId, rawCredential, accountId, "login_active", null);
                }
                Session issued = insertSession(c, accountId, "anonymous", credentialId);
                Map<String, Object> response = map(
                        "accountId", String.valueOf(accountId), "phoneBound", false,
                        "sessionToken", issued.rawToken, "deviceCredential", rawCredential,
                        "deviceCredentialAction", action);
                remember(c, "device_session", actor, idempotencyKey, requestHash, response,
                        now() + RESOLUTION_TTL, issued.id, credentialId);
                audit(c, "device_session_" + action, accountId, issued.id, actor, null);
                return response;
            });
            return raiseDeferred(result);
        } catch (ApiException e) {
            throw e;
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    public Map<String, Object> createChallenge(AuthPrincipal principal, String phone, String purpose,
                                                String intent, String resourceId, String schemaVersion,
                                                String idempotencyKey, String clientIp) {
        requireAnonymous(principal);
        requireIdempotency(idempotencyKey, false);
        String normalized = normalizePhone(phone);
        if (!"login_or_bind".equals(purpose)) throw bad("phone_invalid", "手机号格式不正确，请检查后再试。", null);
        validateContinuation(principal.accountId(), intent, resourceId, schemaVersion);
        String phoneHash = crypto.hash("phone", normalized);
        String requestHash = crypto.hash("request", normalized + "|" + purpose + "|" + intent + "|"
                + resourceId + "|" + schemaVersion);
        try {
            Map<String, Object> result = db.inTransaction(c -> {
                lock(c, "phone-challenge:" + phoneHash);
                Map<String, Object> replay = replay(c, "phone_challenge", String.valueOf(principal.accountId()),
                        idempotencyKey, requestHash, "idempotency_conflict");
                if (replay != null) return replay;
                Challenge latest = latestChallenge(c, phoneHash, purpose);
                long now = now();
                if (latest != null && "created".equals(latest.status) && now < latest.resendAt) {
                    throw conflict("resend_cooldown", "再等一会儿就可以重新发送。",
                            Map.of("retryAfterSeconds", seconds(latest.resendAt - now)));
                }
                rate(c, "phone", phoneHash, 5, 10);
                String deviceDimension = principal.deviceCredentialId() == null
                        ? principal.sessionId() : principal.deviceCredentialId();
                rate(c, "device", crypto.hash("device-rate", deviceDimension), 10, 30);
                rate(c, "ip", crypto.hash("ip", safeIp(clientIp)), 20, 100);
                String code = AuthCrypto.digits(6);
                String challengeId = id();
                try {
                    sms.send(normalized, code, challengeId);
                } catch (Exception e) {
                    throw new ApiException(ApiException.SERVER_ERROR,
                            "验证码暂时没能发出，请稍后再试。", "sms_provider_unavailable");
                }
                try (PreparedStatement supersede = c.prepareStatement(
                        "UPDATE \"t_phone_challenge\" SET \"status\"='superseded' "
                                + "WHERE \"phoneHash\"=? AND \"purpose\"=? AND \"status\"='created'")) {
                    supersede.setString(1, phoneHash);
                    supersede.setString(2, purpose);
                    supersede.executeUpdate();
                }
                long expires = now + CODE_TTL;
                long resend = now + RESEND_DELAY;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO \"t_phone_challenge\"(\"challengeId\",\"accountId\",\"sessionId\","
                                + "\"phoneHash\",\"phoneCipher\",\"codeHash\",\"purpose\",\"continuationIntent\","
                                + "\"resourceId\",\"schemaVersion\",\"status\",\"attemptCount\",\"expiresAt\","
                                + "\"resendAvailableAt\",\"createdAt\") VALUES(?,?,?,?,?,?,?,?,?,?, 'created',0,?,?,?)")) {
                    ps.setString(1, challengeId);
                    ps.setLong(2, principal.accountId());
                    ps.setString(3, principal.sessionId());
                    ps.setString(4, phoneHash);
                    ps.setString(5, crypto.encrypt(normalized));
                    ps.setString(6, crypto.hash("code:" + challengeId, code));
                    ps.setString(7, purpose);
                    ps.setString(8, intent);
                    nullable(ps, 9, resourceId);
                    nullable(ps, 10, schemaVersion);
                    ps.setLong(11, expires);
                    ps.setLong(12, resend);
                    ps.setLong(13, now);
                    ps.executeUpdate();
                }
                Map<String, Object> response = map("challengeId", challengeId, "expiresAt", expires,
                        "resendAvailableAt", resend);
                remember(c, "phone_challenge", String.valueOf(principal.accountId()), idempotencyKey,
                        requestHash, response, expires, null, null);
                audit(c, "phone_challenge_created", principal.accountId(), principal.sessionId(), phoneHash, intent);
                return response;
            });
            return raiseDeferred(result);
        } catch (ApiException e) {
            throw e;
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    public Map<String, Object> verify(AuthPrincipal principal, String challengeId, String code,
                                      String idempotencyKey) {
        requireAnonymous(principal);
        requireIdempotency(idempotencyKey, false);
        String requestHash = crypto.hash("request", challengeId + "|" + code);
        try {
            Map<String, Object> result = db.inTransaction(c -> {
                lock(c, "phone-verify:" + challengeId);
                Map<String, Object> replay = replay(c, "phone_verify", String.valueOf(principal.accountId()),
                        idempotencyKey, requestHash, "idempotency_conflict");
                if (replay != null) return replay;
                Challenge challenge = challenge(c, challengeId, true);
                if (challenge == null || challenge.accountId != principal.accountId()
                        || !challenge.sessionId.equals(principal.sessionId())) {
                    throw conflict("challenge_expired", "这次验证码已经失效，请重新发送。", null);
                }
                long now = now();
                if ("locked".equals(challenge.status)) throw conflict("challenge_locked", "验证码尝试次数已用完，请重新发送。", null);
                if (!"created".equals(challenge.status) || now >= challenge.expiresAt) {
                    expireChallenge(c, challengeId);
                    return deferred("challenge_expired", "这次验证码已经失效，请重新发送。", null);
                }
                if (!crypto.hash("code:" + challengeId, code == null ? "" : code).equals(challenge.codeHash)) {
                    int attempts = challenge.attempts + 1;
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE \"t_phone_challenge\" SET \"attemptCount\"=?,\"status\"=? WHERE \"challengeId\"=?")) {
                        ps.setInt(1, attempts);
                        ps.setString(2, attempts >= 5 ? "locked" : "created");
                        ps.setString(3, challengeId);
                        ps.executeUpdate();
                    }
                    Map<String, Object> failure = attempts >= 5
                            ? deferred("challenge_locked", "验证码尝试次数已用完，请重新发送。", null)
                            : deferred("code_invalid", "验证码不正确，请再试一次。",
                                    Map.of("remainingAttempts", 5 - attempts));
                    // 错码会消耗挑战次数，因此失败结果本身也必须进入幂等记录。
                    // 网络重试使用同一 key 时只重放原失败，不能再次扣减 attemptCount。
                    remember(c, "phone_verify", String.valueOf(principal.accountId()), idempotencyKey,
                            requestHash, failure, challenge.expiresAt, null, null);
                    return failure;
                }
                Long target = phoneOwner(c, challenge.phoneHash, false);
                String resolution = target == null ? "bind_current" : "switch_existing";
                String rawToken = AuthCrypto.token(32);
                String resolutionId = id();
                long expires = now + RESOLUTION_TTL;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO \"t_phone_resolution\"(\"resolutionId\",\"tokenHash\",\"challengeId\","
                                + "\"sourceAccountId\",\"sourceSessionId\",\"resolution\",\"targetAccountId\","
                                + "\"phoneHash\",\"phoneCipher\",\"continuationIntent\",\"resourceId\","
                                + "\"schemaVersion\",\"status\",\"expiresAt\") "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?, 'created',?)")) {
                    ps.setString(1, resolutionId);
                    ps.setString(2, crypto.hash("resolution", rawToken));
                    ps.setString(3, challengeId);
                    ps.setLong(4, principal.accountId());
                    ps.setString(5, principal.sessionId());
                    ps.setString(6, resolution);
                    if (target == null) ps.setNull(7, Types.BIGINT); else ps.setLong(7, target);
                    ps.setString(8, challenge.phoneHash);
                    ps.setString(9, challenge.phoneCipher);
                    ps.setString(10, challenge.intent);
                    nullable(ps, 11, challenge.resourceId);
                    nullable(ps, 12, challenge.schemaVersion);
                    ps.setLong(13, expires);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE \"t_phone_challenge\" SET \"status\"='verified' WHERE \"challengeId\"=?")) {
                    ps.setString(1, challengeId);
                    ps.executeUpdate();
                }
                Map<String, Object> response = map("resolution", resolution, "resolutionToken", rawToken,
                        "resolutionExpiresAt", expires);
                remember(c, "phone_verify", String.valueOf(principal.accountId()), idempotencyKey, requestHash,
                        response, expires, null, null);
                audit(c, "phone_challenge_verified", principal.accountId(), principal.sessionId(), challenge.phoneHash, resolution);
                return response;
            });
            return raiseDeferred(result);
        } catch (ApiException e) {
            throw e;
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw conflict("idempotency_conflict", "这次操作已经处理，请不要重复提交。", null);
            }
            throw unavailable(e);
        }
    }

    public Map<String, Object> confirm(String bearerToken, String resolutionToken, String idempotencyKey) {
        requireIdempotency(idempotencyKey, false);
        if (resolutionToken == null || !OPAQUE.matcher(resolutionToken).matches()) {
            throw conflict("resolution_expired", "这次登录确认已经失效，请重新验证。", null);
        }
        String presentedSessionHash = crypto.hash("session", bearerToken == null ? "" : bearerToken);
        String requestHash = crypto.hash("request", resolutionToken);
        try {
            Map<String, Object> result = db.inTransaction(c -> {
                Resolution r = resolution(c, crypto.hash("resolution", resolutionToken), true);
                if (r == null) throw conflict("resolution_expired", "这次登录确认已经失效，请重新验证。", null);
                SessionRow source = session(c, r.sourceSessionId, true);
                if (source == null || !source.tokenHash.equals(presentedSessionHash)
                        || source.accountId != r.sourceAccountId) {
                    throw conflict("resolution_session_mismatch", "请回到刚才验证手机号的账号继续。", null);
                }
                lock(c, "phone-confirm:" + r.id);
                Map<String, Object> replay = replay(c, "phone_confirm", String.valueOf(r.sourceAccountId),
                        idempotencyKey, requestHash, "idempotency_conflict");
                if (replay != null) return replay;
                if (!"active".equals(source.status)) {
                    throw conflict("resolution_used", "这次登录确认已经处理完成。", null);
                }
                if (!"created".equals(r.status)) throw conflict("resolution_used", "这次登录确认已经处理完成。", null);
                if (now() >= r.expiresAt) throw conflict("resolution_expired", "这次登录确认已经失效，请重新验证。", null);

                Long currentOwner = phoneOwner(c, r.phoneHash, true);
                if ("bind_current".equals(r.resolution) && currentOwner != null
                        || "switch_existing".equals(r.resolution) && !Objects.equals(currentOwner, r.targetAccountId)) {
                    throw conflict("phone_ownership_changed", "手机号归属刚刚发生变化，请重新验证。", null);
                }
                Map<String, Object> response;
                Session issued;
                String resultDeviceCredentialId = null;
                if ("bind_current".equals(r.resolution)) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO \"t_phone_credential\"(\"phoneHash\",\"phoneCipher\",\"accountId\",\"createdAt\") VALUES(?,?,?,?)")) {
                        ps.setString(1, r.phoneHash);
                        ps.setString(2, r.phoneCipher);
                        ps.setLong(3, r.sourceAccountId);
                        ps.setLong(4, now());
                        ps.executeUpdate();
                    }
                    setBound(c, r.sourceAccountId);
                    revokeAccountAnonymousSessions(c, r.sourceAccountId, "bound");
                    revokeAccountDevices(c, r.sourceAccountId, "bound");
                    issued = insertSession(c, r.sourceAccountId, "bound", null);
                    ContinuationResult continuation = continuation(r.sourceAccountId, r, true);
                    response = map("accountId", String.valueOf(r.sourceAccountId), "phoneBound", true,
                            "sessionToken", issued.rawToken, "deviceCredential", null,
                            "returnToAllowed", continuation.allowed, "nextAction", continuation.nextAction,
                            "previousAnonymousCredentialDisposition", "revoked", "anonymousRecovery", null);
                    audit(c, "phone_bound_current", r.sourceAccountId, issued.id, r.phoneHash, null);
                } else {
                    revokeSession(c, r.sourceSessionId, "switched_account");
                    if (source.deviceCredentialId != null) revokeDevice(c, source.deviceCredentialId, "switched_account");
                    String recoveryRaw = AuthCrypto.token(32);
                    resultDeviceCredentialId = id();
                    insertDevice(c, resultDeviceCredentialId, recoveryRaw, r.sourceAccountId, "recovery_only", null);
                    issued = insertSession(c, r.targetAccountId, "bound", null);
                    response = map("accountId", String.valueOf(r.targetAccountId), "phoneBound", true,
                            "sessionToken", issued.rawToken, "deviceCredential", null,
                            "returnToAllowed", false, "nextAction",
                            "private_onboarding_generation".equals(r.intent) ? "restart_in_existing_account" : "none",
                            "previousAnonymousCredentialDisposition", "retained_as_recovery",
                            "anonymousRecovery", Map.of("recoveryCredential", recoveryRaw));
                    audit(c, "phone_switched_existing", r.targetAccountId, issued.id, r.phoneHash,
                            "sourceAnonymousAccountId=" + r.sourceAccountId);
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE \"t_phone_resolution\" SET \"status\"='used',\"usedAt\"=? WHERE \"resolutionId\"=?")) {
                    ps.setLong(1, now());
                    ps.setString(2, r.id);
                    ps.executeUpdate();
                }
                remember(c, "phone_confirm", String.valueOf(r.sourceAccountId), idempotencyKey, requestHash,
                        response, r.expiresAt, issued.id, resultDeviceCredentialId);
                return response;
            });
            return raiseDeferred(result);
        } catch (ApiException e) {
            throw e;
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw conflict("phone_ownership_changed", "手机号归属刚刚发生变化，请重新验证。", null);
            }
            throw unavailable(e);
        }
    }

    private ContinuationResult continuation(long accountId, Resolution r, boolean binding) {
        if ("none".equals(r.intent)) return new ContinuationResult(false, "none");
        if (binding && continuations.canResume(accountId, r.intent, r.resourceId, r.schemaVersion)) {
            return new ContinuationResult(true, "resume_private_onboarding");
        }
        return new ContinuationResult(false, "open_private_onboarding");
    }

    private void validateContinuation(long accountId, String intent, String resourceId, String schemaVersion) {
        if ("none".equals(intent) && resourceId == null && schemaVersion == null) return;
        if (!"private_onboarding_generation".equals(intent) || resourceId == null
                || !"v1".equals(schemaVersion)
                || !continuations.canResume(accountId, intent, resourceId, schemaVersion)) {
            throw bad("continuation_invalid", "刚才的页面状态已经变化，请回去刷新后再试。", null);
        }
    }

    private void requireAnonymous(AuthPrincipal principal) {
        if (principal == null || !principal.anonymous()) {
            throw conflict("resolution_session_mismatch", "请从当前匿名体验继续手机号登录。", null);
        }
    }

    private static void requireIdempotency(String key, boolean device) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw bad(device ? "device_session_idempotency_conflict" : "idempotency_conflict",
                    "请重新提交这次操作。", null);
        }
    }

    private String normalizePhone(String phone) {
        String normalized = phone == null ? "" : phone.replace(" ", "").replace("-", "");
        if (!E164.matcher(normalized).matches()) throw bad("phone_invalid", "手机号格式不正确，请检查后再试。", null);
        return normalized;
    }

    private void rate(Connection c, String dimension, String subject, int hourly, int daily) throws SQLException {
        long now = now();
        long hourCount = countRate(c, dimension, subject, now - 3_600_000L);
        long dayCount = countRate(c, dimension, subject, now - 86_400_000L);
        if (hourCount >= hourly || dayCount >= daily) {
            throw new ApiException(ApiException.RULE_FORBIDDEN, "操作有点频繁，请稍后再试。", "rate_limited",
                    Map.of("retryAfterSeconds", 60));
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO \"t_auth_rate_event\"(\"eventId\",\"dimension\",\"subjectHash\",\"createdAt\") VALUES(?,?,?,?)")) {
            ps.setString(1, id());
            ps.setString(2, dimension);
            ps.setString(3, subject);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    private long countRate(Connection c, String dimension, String subject, long since) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM \"t_auth_rate_event\" WHERE \"dimension\"=? AND \"subjectHash\"=? AND \"createdAt\">=?")) {
            ps.setString(1, dimension);
            ps.setString(2, subject);
            ps.setLong(3, since);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private Map<String, Object> replay(Connection c, String operation, String actor, String key,
                                       String hash, String conflictDetail) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT \"requestHash\",\"responseCipher\",\"status\",\"replayUntil\" FROM \"t_auth_idempotency\" "
                        + "WHERE \"operation\"=? AND \"actorScope\"=? AND \"idempotencyKey\"=?")) {
            ps.setString(1, operation); ps.setString(2, actor); ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                if (!hash.equals(rs.getString(1))) throw conflict(conflictDetail, "这次提交与已处理的内容不一致。", null);
                if (now() >= rs.getLong(4)) {
                    try (PreparedStatement clear = c.prepareStatement(
                            "UPDATE \"t_auth_idempotency\" SET \"responseCipher\"=NULL,\"status\"='expired',"
                                    + "\"updatedAt\"=? WHERE \"operation\"=? AND \"actorScope\"=? AND \"idempotencyKey\"=?")) {
                        clear.setLong(1, now()); clear.setString(2, operation); clear.setString(3, actor); clear.setString(4, key);
                        clear.executeUpdate();
                    }
                    String expiredDetail = switch (operation) {
                        case "phone_challenge" -> "challenge_expired";
                        case "phone_verify" -> "resolution_expired";
                        case "phone_confirm" -> "resolution_used";
                        default -> "device_session_idempotency_conflict";
                    };
                    return deferred(expiredDetail, "这次操作的安全恢复窗口已经结束，请重新开始。", null);
                }
                if (!"completed".equals(rs.getString(3)) || rs.getString(2) == null) {
                    throw conflict(conflictDetail, "这次操作仍在处理中，请稍后重试。", null);
                }
                return GSON.fromJson(crypto.decrypt(rs.getString(2)),
                        new TypeToken<LinkedHashMap<String, Object>>() { }.getType());
            }
        }
    }

    private void remember(Connection c, String operation, String actor, String key,
                          String hash, Map<String, Object> response, long replayUntil,
                          String resultSessionId, String resultDeviceCredentialId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO \"t_auth_idempotency\"(\"operation\",\"actorScope\",\"idempotencyKey\","
                        + "\"requestHash\",\"responseCipher\",\"status\",\"replayUntil\","
                        + "\"resultSessionId\",\"resultDeviceCredentialId\",\"createdAt\",\"updatedAt\") "
                        + "VALUES(?,?,?,?,?,'completed',?,?,?,?,?)")) {
            ps.setString(1, operation); ps.setString(2, actor); ps.setString(3, key); ps.setString(4, hash);
            ps.setString(5, crypto.encrypt(GSON.toJson(response))); ps.setLong(6, replayUntil);
            nullable(ps, 7, resultSessionId); nullable(ps, 8, resultDeviceCredentialId);
            ps.setLong(9, now()); ps.setLong(10, now());
            ps.executeUpdate();
        }
    }

    private Session insertSession(Connection c, long accountId, String kind, String deviceCredentialId) throws SQLException {
        String raw = AuthCrypto.token(32); String id = id();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO \"t_auth_session\"(\"sessionId\",\"tokenHash\",\"accountId\",\"kind\","
                        + "\"status\",\"deviceCredentialId\",\"createdAt\") VALUES(?,?,?,?,'active',?,?)")) {
            ps.setString(1, id); ps.setString(2, crypto.hash("session", raw)); ps.setLong(3, accountId);
            ps.setString(4, kind); nullable(ps, 5, deviceCredentialId); ps.setLong(6, now()); ps.executeUpdate();
        }
        return new Session(id, raw);
    }

    private long createAnonymousAccount(Connection c) throws SQLException {
        long accountId = ids.nextId();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO \"t_account\"(\"id\",\"openId\",\"status\",\"createTime\") VALUES(?,?,0,?)")) {
            ps.setLong(1, accountId); ps.setString(2, "anon:" + AuthCrypto.token(18)); ps.setLong(3, now()); ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO \"t_account_profile\"(\"accountId\",\"deviceId\",\"guest\",\"createTime\") VALUES(?,?,1,?)")) {
            ps.setLong(1, accountId); ps.setString(2, "auth-" + id()); ps.setLong(3, now()); ps.executeUpdate();
        }
        return accountId;
    }

    private void insertDevice(Connection c, String id, String raw, long accountId, String status, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO \"t_device_credential\"(\"credentialId\",\"credentialHash\",\"accountId\","
                        + "\"status\",\"revocationReason\",\"createdAt\",\"updatedAt\") VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, crypto.hash("device", raw)); ps.setLong(3, accountId);
            ps.setString(4, status); nullable(ps, 5, reason); ps.setLong(6, now()); ps.setLong(7, now()); ps.executeUpdate();
        }
    }

    private Device deviceByHash(Connection c, String hash, boolean lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT \"credentialId\",\"accountId\",\"status\",\"revocationReason\" FROM \"t_device_credential\" "
                        + "WHERE \"credentialHash\"=?" + (lock ? " FOR UPDATE" : ""))) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Device(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)) : null;
            }
        }
    }

    private boolean isAnonymous(Connection c, long accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT \"guest\" FROM \"t_account_profile\" WHERE \"accountId\"=?")) {
            ps.setLong(1, accountId); try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getInt(1) != 0; }
        }
    }

    private Challenge latestChallenge(Connection c, String phoneHash, String purpose) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT \"challengeId\",\"accountId\",\"sessionId\",\"phoneHash\",\"phoneCipher\","
                        + "\"codeHash\",\"continuationIntent\",\"resourceId\",\"schemaVersion\",\"status\","
                        + "\"attemptCount\",\"expiresAt\",\"resendAvailableAt\" FROM \"t_phone_challenge\" "
                        + "WHERE \"phoneHash\"=? AND \"purpose\"=? ORDER BY \"createdAt\" DESC LIMIT 1")) {
            ps.setString(1, phoneHash); ps.setString(2, purpose);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? challengeRow(rs) : null; }
        }
    }

    private Challenge challenge(Connection c, String id, boolean lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT \"challengeId\",\"accountId\",\"sessionId\",\"phoneHash\",\"phoneCipher\","
                        + "\"codeHash\",\"continuationIntent\",\"resourceId\",\"schemaVersion\",\"status\","
                        + "\"attemptCount\",\"expiresAt\",\"resendAvailableAt\" FROM \"t_phone_challenge\" "
                        + "WHERE \"challengeId\"=?" + (lock ? " FOR UPDATE" : ""))) {
            ps.setString(1, id); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? challengeRow(rs) : null; }
        }
    }

    private static Challenge challengeRow(ResultSet rs) throws SQLException {
        return new Challenge(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getInt(11), rs.getLong(12), rs.getLong(13));
    }

    private Resolution resolution(Connection c, String tokenHash, boolean lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT \"resolutionId\",\"sourceAccountId\",\"sourceSessionId\",\"resolution\","
                        + "\"targetAccountId\",\"phoneHash\",\"phoneCipher\",\"continuationIntent\","
                        + "\"resourceId\",\"schemaVersion\",\"status\",\"expiresAt\" FROM \"t_phone_resolution\" "
                        + "WHERE \"tokenHash\"=?" + (lock ? " FOR UPDATE" : ""))) {
            ps.setString(1, tokenHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Long target = rs.getObject(5) == null ? null : rs.getLong(5);
                return new Resolution(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), target,
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                        rs.getString(11), rs.getLong(12));
            }
        }
    }

    private SessionRow session(Connection c, String id, boolean lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT \"tokenHash\",\"accountId\",\"kind\",\"status\",\"deviceCredentialId\" "
                        + "FROM \"t_auth_session\" WHERE \"sessionId\"=?" + (lock ? " FOR UPDATE" : ""))) {
            ps.setString(1, id); try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new SessionRow(id, rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)) : null;
            }
        }
    }

    private Long phoneOwner(Connection c, String hash, boolean lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT \"accountId\" FROM \"t_phone_credential\" WHERE \"phoneHash\"=?" + (lock ? " FOR UPDATE" : ""))) {
            ps.setString(1, hash); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : null; }
        }
    }

    private void setBound(Connection c, long accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE \"t_account_profile\" SET \"guest\"=0 WHERE \"accountId\"=?")) {
            ps.setLong(1, accountId); if (ps.executeUpdate() != 1) throw new SQLException("account profile missing");
        }
    }

    private void revokeAccountAnonymousSessions(Connection c, long accountId, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE \"t_auth_session\" SET \"status\"='revoked',\"revokedAt\"=? "
                        + "WHERE \"accountId\"=? AND \"kind\"='anonymous' AND \"status\"='active'")) {
            ps.setLong(1, now()); ps.setLong(2, accountId); ps.executeUpdate();
        }
        clearCredentialReplayForAccount(c, "resultSessionId", "t_auth_session", "sessionId", accountId);
    }

    private void revokeAccountDevices(Connection c, long accountId, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE \"t_device_credential\" SET \"status\"='revoked',\"revocationReason\"=?,\"updatedAt\"=? "
                        + "WHERE \"accountId\"=? AND \"status\"='login_active'")) {
            ps.setString(1, reason); ps.setLong(2, now()); ps.setLong(3, accountId); ps.executeUpdate();
        }
        clearCredentialReplayForAccount(c, "resultDeviceCredentialId", "t_device_credential", "credentialId", accountId);
    }

    private void revokeSession(Connection c, String id, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE \"t_auth_session\" SET \"status\"='revoked',\"revokedAt\"=? WHERE \"sessionId\"=?")) {
            ps.setLong(1, now()); ps.setString(2, id); ps.executeUpdate();
        }
        clearCredentialReplay(c, "resultSessionId", id);
    }

    private void revokeDevice(Connection c, String id, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE \"t_device_credential\" SET \"status\"='revoked',\"revocationReason\"=?,\"updatedAt\"=? WHERE \"credentialId\"=?")) {
            ps.setString(1, reason); ps.setLong(2, now()); ps.setString(3, id); ps.executeUpdate();
        }
        clearCredentialReplay(c, "resultDeviceCredentialId", id);
    }

    private void clearCredentialReplay(Connection c, String referenceColumn, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE \"t_auth_idempotency\" SET \"responseCipher\"=NULL,\"status\"='revoked',"
                        + "\"updatedAt\"=? WHERE \"" + referenceColumn + "\"=? AND \"responseCipher\" IS NOT NULL")) {
            ps.setLong(1, now()); ps.setString(2, id); ps.executeUpdate();
        }
    }

    private void clearCredentialReplayForAccount(Connection c, String referenceColumn, String table,
                                                   String idColumn, long accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE \"t_auth_idempotency\" SET \"responseCipher\"=NULL,\"status\"='revoked',"
                        + "\"updatedAt\"=? WHERE \"" + referenceColumn + "\" IN (SELECT \"" + idColumn
                        + "\" FROM \"" + table + "\" WHERE \"accountId\"=?) AND \"responseCipher\" IS NOT NULL")) {
            ps.setLong(1, now()); ps.setLong(2, accountId); ps.executeUpdate();
        }
    }

    private void expireChallenge(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE \"t_phone_challenge\" SET \"status\"='expired' WHERE \"challengeId\"=? AND \"status\"='created'")) {
            ps.setString(1, id); ps.executeUpdate();
        }
    }

    private void audit(Connection c, String event, Long accountId, String sessionId, String subject, String detail) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO \"t_auth_audit\"(\"auditId\",\"eventType\",\"accountId\",\"sessionId\","
                        + "\"subjectHash\",\"detail\",\"createdAt\") VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, id()); ps.setString(2, event);
            if (accountId == null) ps.setNull(3, Types.BIGINT); else ps.setLong(3, accountId);
            nullable(ps, 4, sessionId); nullable(ps, 5, subject); nullable(ps, 6, detail); ps.setLong(7, now());
            ps.executeUpdate();
        }
    }

    private void lock(Connection c, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
            ps.setString(1, value); ps.executeQuery().close();
        }
    }

    private String id() { return String.valueOf(ids.nextId()); }
    private long now() { return clock.millis(); }
    private static long seconds(long millis) { return Math.max(1, (millis + 999) / 1000); }
    private static String safeIp(String ip) { return ip == null || ip.isBlank() ? "unknown" : ip; }
    private static void nullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) ps.setNull(index, Types.VARCHAR); else ps.setString(index, value);
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = Json.map();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private static Map<String, Object> deferred(String detail, String message, Map<String, Object> data) {
        return map("__authError", detail, "__authMessage", message, "__authData", data);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> raiseDeferred(Map<String, Object> result) {
        String detail = (String) result.get("__authError");
        if (detail == null) return result;
        Map<String, Object> data = (Map<String, Object>) result.get("__authData");
        if ("code_invalid".equals(detail)) throw bad(detail, (String) result.get("__authMessage"), data);
        throw conflict(detail, (String) result.get("__authMessage"), data);
    }

    private static ApiException bad(String detail, String message, Map<String, Object> data) {
        return new ApiException(ApiException.BAD_PARAM, message, detail, data);
    }
    private static ApiException conflict(String detail, String message, Map<String, Object> data) {
        return new ApiException(ApiException.RULE_FORBIDDEN, message, detail, data);
    }
    private static ApiException unavailable(Exception cause) {
        return new ApiException(ApiException.SERVER_ERROR, "身份服务暂时不可用，请稍后再试。",
                "auth_persistence_unavailable");
    }

    private record Session(String id, String rawToken) { }
    private record SessionRow(String id, String tokenHash, long accountId, String kind, String status,
                              String deviceCredentialId) { }
    private record Device(String id, long accountId, String status, String reason) { }
    private record Challenge(String id, long accountId, String sessionId, String phoneHash, String phoneCipher,
                             String codeHash, String intent, String resourceId, String schemaVersion, String status,
                             int attempts, long expiresAt, long resendAt) { }
    private record Resolution(String id, long sourceAccountId, String sourceSessionId, String resolution,
                              Long targetAccountId, String phoneHash, String phoneCipher, String intent,
                              String resourceId, String schemaVersion, String status, long expiresAt) { }
    private record ContinuationResult(boolean allowed, String nextAction) { }
}
