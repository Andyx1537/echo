package com.echo.http.work;

/**
 * 作品审核工单。与回忆卡 {@code t_moderation} 分表：对象状态不能共用。
 *
 * <p>审核队列活动态 {@code queued|assigned|reviewing}；申诉中另算未完结工单。
 * 过审、驳回、下架、取消释放「同一作品仅一张未完结工单」约束。</p>
 */
public final class WorkModerationTicket {

    public static final class State {
        public static final String QUEUED = "queued";
        public static final String ASSIGNED = "assigned";
        public static final String REVIEWING = "reviewing";
        public static final String APPROVED = "approved";
        public static final String REJECTED = "rejected";
        public static final String CANCELLED = "cancelled";
        public static final String TAKENDOWN = "takendown";
        public static final String APPEALING = "appealing";

        private State() {
        }

        public static boolean active(String state) {
            return QUEUED.equals(state) || ASSIGNED.equals(state) || REVIEWING.equals(state);
        }

        /** 未完结工单：审核队列加上申诉中，挡住同作品再开一张。 */
        public static boolean inflight(String state) {
            return active(state) || APPEALING.equals(state);
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
    public String appealText;
    public Long appealAt;
    public String preAppealStatus;
    public String appealResult;
    public Long appealHandledBy;
    public Long appealHandledAt;

    public boolean appealUsed() {
        return appealAt != null;
    }

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

    public static WorkModerationTicket approved(long id, Work work, long now) {
        WorkModerationTicket t = queued(id, work, now);
        t.state = State.APPROVED;
        t.handledAt = now;
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
