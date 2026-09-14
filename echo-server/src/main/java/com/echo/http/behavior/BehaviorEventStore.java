package com.echo.http.behavior;

import com.echo.infra.persistence.PgDb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 行为事实仓储。本账本验收走内存；PG 表已建，接线可后做。 */
public final class BehaviorEventStore {
    private final Map<String, BehaviorEvent> byId = new ConcurrentHashMap<>();
    private final Map<String, String> byIdempotency = new ConcurrentHashMap<>();

    public BehaviorEventStore(PgDb db) {
        if (db != null) {
            // schema 已建表；本账本验收走内存
        }
    }

    public BehaviorEvent byIdempotency(long accountId, String key) {
        String eventId = byIdempotency.get(accountId + ":" + key);
        return eventId == null ? null : byId.get(eventId);
    }

    public BehaviorEvent putIfAbsent(BehaviorEvent event) {
        String idem = event.accountId + ":" + event.idempotencyKey;
        String existingId = byIdempotency.putIfAbsent(idem, event.eventId);
        if (existingId != null) {
            return byId.get(existingId);
        }
        byId.put(event.eventId, event);
        return event;
    }

    public List<BehaviorEvent> ofAccount(long accountId) {
        List<BehaviorEvent> out = new ArrayList<>();
        for (BehaviorEvent event : byId.values()) {
            if (event.accountId == accountId) {
                out.add(event);
            }
        }
        return out;
    }
}
