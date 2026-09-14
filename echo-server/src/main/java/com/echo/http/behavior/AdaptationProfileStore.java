package com.echo.http.behavior;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 在线适配开关。清除只退出生效链，不删事实。 */
public final class AdaptationProfileStore {
    public static final String PERSONALIZED = "personalized";
    public static final String NON_PERSONALIZED = "non_personalized";

    private final ConcurrentHashMap<Long, String> modes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> cleared = new ConcurrentHashMap<>();

    public String mode(long accountId) {
        return modes.getOrDefault(accountId, PERSONALIZED);
    }

    public void setMode(long accountId, String mode) {
        modes.put(accountId, mode);
    }

    public boolean enabled(long accountId, String scope) {
        if (BehaviorDictionary.PUBLIC.equals(scope) && NON_PERSONALIZED.equals(mode(accountId))) {
            return false;
        }
        Set<String> off = cleared.get(accountId);
        return off == null || !off.contains(scope);
    }

    public void clear(long accountId, String scope) {
        cleared.computeIfAbsent(accountId, id -> ConcurrentHashMap.newKeySet()).add(scope);
    }
}
