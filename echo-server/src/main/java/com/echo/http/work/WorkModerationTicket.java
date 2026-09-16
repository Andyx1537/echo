package com.echo.http.work;

/**
 * 作品审核工单。与回忆卡 {@code t_moderation} 分表：对象状态不能共用。
 *
 * <p>活动态只有 {@code queued|assigned|reviewing}；过审、驳回、取消是终态，
 * 释放「同一作品仅一张活动工单」约束。</p>
 */
public final class WorkModerationTicket {

    public static final class State {
        public static final String QUEUED = "queued";
        public static final String ASSIGNED = "assigned";
        public static final String REVIEWING = "reviewing";
        public static final String APPROVED = "approved";
        public static final String REJECTED = "rejected";
        public static final String CANCELLED = "cancelled";

        private State() {
        }

        public static boolean active(String state) {
            return QUEUED.equals(state) || ASSIGNED.equals(state) || REVIEWING.equals(state);
        }
    }

    public long id;
    public long workId;
    public int contentVersion = 1;
    public String state = State.QUEUED;
    public int stateVersion = 1;
    public long submitBy;
    public String reasonCode;
    public String note;
    public String snapshotJson;
    public Long handledBy;
    public Long handledAt;
    public long createdAt;

    public static WorkModerationTicket queued(long id, Work work, long now) {
        WorkModerationTicket t = new WorkModerationTicket();
        t.id = id;
        t.workId = work.id;
        t.contentVersion = work.contentVersion;
        t.state = State.QUEUED;
        t.stateVersion = 1;
        t.submitBy = work.authorId;
        t.snapshotJson = snapshot(work);
        t.createdAt = now;
        return t;
    }

    static String snapshot(Work work) {
        return "{\"title\":\"" + escape(work.title) + "\",\"body\":\"" + escape(work.body)
                + "\",\"mediaKey\":\"" + escape(work.mediaKey) + "\",\"contentVersion\":"
                + work.contentVersion + ",\"contentHash\":\"" + escape(work.contentHash) + "\"}";
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
