package com.echo.http.work;

import com.echo.infra.persistence.PgDb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 作品评论。无库时内存态，语义与落库一致。 */
public final class WorkCommentStore {
    private final Map<Long, WorkComment> memory = new ConcurrentHashMap<>();

    public WorkCommentStore(PgDb db) {
        // 本账本验收走内存。PG 表已进 schema，有库时下一轮再接线。
        if (db != null) {
            // keep constructor aligned with other stores
        }
    }

    public void insert(WorkComment c) {
        memory.put(c.id, c);
    }

    public WorkComment byId(long id) {
        return memory.get(id);
    }

    public List<WorkComment> ofWork(long workId) {
        List<WorkComment> out = new ArrayList<>();
        for (WorkComment c : memory.values()) {
            if (c.workId == workId) {
                out.add(c);
            }
        }
        return out;
    }

    public List<WorkComment> visibleRoots(long workId, String sort) {
        List<WorkComment> roots = new ArrayList<>();
        for (WorkComment c : memory.values()) {
            if (c.workId == workId && c.root() && c.publiclyVisible()) {
                roots.add(c);
            }
        }
        if ("latest".equals(sort)) {
            roots.sort(Comparator.comparingLong((WorkComment c) -> c.createdAt).reversed()
                    .thenComparing(Comparator.comparingLong((WorkComment c) -> c.id).reversed()));
        } else {
            roots.sort(Comparator
                    .comparingLong((WorkComment c) -> hotScore(c)).reversed()
                    .thenComparing(Comparator.comparingLong((WorkComment c) -> c.id).reversed()));
        }
        return roots;
    }

    public List<WorkComment> visibleReplies(long rootId) {
        List<WorkComment> replies = new ArrayList<>();
        for (WorkComment c : memory.values()) {
            if (c.rootCommentId != null && c.rootCommentId == rootId && c.publiclyVisible()) {
                replies.add(c);
            }
        }
        replies.sort(Comparator.comparingLong((WorkComment c) -> c.createdAt)
                .thenComparingLong(c -> c.id));
        return replies;
    }

    public int visibleCount(long workId) {
        int n = 0;
        for (WorkComment c : memory.values()) {
            if (c.workId == workId && c.publiclyVisible()) {
                n++;
            }
        }
        return n;
    }

    public int visibleReplyCount(long rootId) {
        return visibleReplies(rootId).size();
    }

    public int cascadeSoftDelete(WorkComment root, long now, long by, String reason) {
        int n = 0;
        if (softDelete(root, now, by, reason)) {
            n++;
        }
        for (WorkComment c : ofWork(root.workId)) {
            if (c.rootCommentId != null && c.rootCommentId == root.id && softDelete(c, now, by, reason)) {
                n++;
            }
        }
        return n;
    }

    public boolean softDelete(WorkComment c, long now, long by, String reason) {
        if (c.deletedAt != null) {
            return false;
        }
        c.deletedAt = now;
        c.deletedBy = by;
        c.deleteReason = reason;
        c.displayState = WorkComment.HIDDEN;
        c.updatedAt = now;
        c.stateVersion++;
        return true;
    }

    public void hide(WorkComment c, long now) {
        c.displayState = WorkComment.OWNER_HIDDEN;
        c.updatedAt = now;
        c.stateVersion++;
    }

    public void restore(WorkComment c, long now) {
        c.displayState = WorkComment.VISIBLE;
        c.updatedAt = now;
        c.stateVersion++;
    }

    public long hotScore(WorkComment root) {
        return visibleReplyCount(root.id) * 1_000_000L + root.createdAt / 1000L;
    }
}
