package com.echo.http.work;

/** 作品评论。一级 {@code rootCommentId=null}；回复永远挂在一级下，不长第三层。 */
public final class WorkComment {
    public static final String VISIBLE = "visible";
    public static final String HIDDEN = "hidden";
    public static final String OWNER_HIDDEN = "owner_hidden";

    public long id;
    public long workId;
    public long authorId;
    public Long rootCommentId;
    public Long replyToCommentId;
    public String body = "";
    public long createdAt;
    public long updatedAt;
    public String displayState = VISIBLE;
    public int stateVersion = 1;
    public Long deletedAt;
    public Long deletedBy;
    public String deleteReason;

    public boolean root() {
        return rootCommentId == null;
    }

    public boolean publiclyVisible() {
        return deletedAt == null && VISIBLE.equals(displayState);
    }
}
