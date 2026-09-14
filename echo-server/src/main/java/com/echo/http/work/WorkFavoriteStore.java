package com.echo.http.work;

import com.echo.infra.persistence.PgDb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 收藏是收藏者私有事实。公开 DTO 不得带收藏数。 */
public final class WorkFavoriteStore {
    private final Map<String, Long> memory = new ConcurrentHashMap<>();

    public WorkFavoriteStore(PgDb db) {
        if (db != null) {
            // schema 已建表；本账本验收走内存
        }
    }

    public boolean favorited(long accountId, long workId) {
        return memory.containsKey(key(accountId, workId));
    }

    public void put(long accountId, long workId, long now) {
        memory.putIfAbsent(key(accountId, workId), now);
    }

    public void remove(long accountId, long workId) {
        memory.remove(key(accountId, workId));
    }

    public List<Long> workIdsOf(long accountId) {
        List<long[]> rows = new ArrayList<>();
        String prefix = accountId + ":";
        for (Map.Entry<String, Long> e : memory.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                long workId = Long.parseLong(e.getKey().substring(prefix.length()));
                rows.add(new long[]{workId, e.getValue()});
            }
        }
        rows.sort(Comparator.comparingLong((long[] r) -> r[1]).reversed().thenComparingLong(r -> r[0]));
        List<Long> ids = new ArrayList<>();
        for (long[] r : rows) {
            ids.add(r[0]);
        }
        return ids;
    }

    private static String key(long accountId, long workId) {
        return accountId + ":" + workId;
    }
}
