package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.governance.BlockService;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.store.EchoStore;
import com.echo.http.work.Work;
import com.echo.http.work.WorkComment;
import com.echo.http.work.WorkCommentStore;
import com.echo.http.behavior.BehaviorLedger;
import com.echo.http.work.WorkFavoriteStore;
import com.echo.http.work.WorkStore;
import com.echo.http.work.WorkView;
import com.echo.infra.storage.IStorage;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 作品评论与收藏。公开互动只认 {@code workId}。
 */
public final class WorkSocialApi {
    static final int RANKING_VERSION = 1;
    private static final int PREVIEW_ROOTS = 3;
    private static final int PREVIEW_REPLIES = 2;
    private static final int PAGE_DEFAULT = 20;
    private static final int PAGE_MAX = 50;
    private static final int MAX_BODY = 200;

    private final WorkStore works;
    private final WorkCommentStore comments;
    private final WorkFavoriteStore favorites;
    private final EchoStore accounts;
    private final IDGenerator ids;
    private final IStorage storage;
    private BlockService blocks;
    private BehaviorLedger ledger;
    private final Map<String, Idempotent> idempotency = new ConcurrentHashMap<>();

    public WorkSocialApi(WorkStore works, WorkCommentStore comments, WorkFavoriteStore favorites,
                         EchoStore accounts, IStorage storage, IDGenerator ids) {
        this.works = works;
        this.comments = comments;
        this.favorites = favorites;
        this.accounts = accounts;
        this.storage = storage;
        this.ids = ids;
    }

    public void setBlockService(BlockService blocks) {
        this.blocks = blocks;
    }

    public void setBehaviorLedger(BehaviorLedger ledger) {
        this.ledger = ledger;
    }

    public void register(Router r) {
        r.add("GET", "/works/:workId/comments", this::listComments);
        r.add("GET", "/comments/:rootCommentId/replies", this::listReplies);
        r.add("POST", "/works/:workId/comments", this::createRoot);
        r.add("POST", "/comments/:commentId/replies", this::createReply);
        r.add("DELETE", "/comments/:commentId", this::remove);
        r.add("PUT", "/works/:workId/comments/:commentId/hidden", this::hide);
        r.add("DELETE", "/works/:workId/comments/:commentId/hidden", this::restore);
        r.add("GET", "/works/:workId/comment-governance", this::governance);
        r.add("PUT", "/works/:workId/favorite", this::favorite);
        r.add("DELETE", "/works/:workId/favorite", this::unfavorite);
        r.add("GET", "/me/favorites", this::myFavorites);
    }

    private Object listComments(RequestContext ctx) {
        Work work = visibleWork(ctx, parseId(ctx.path("workId")));
        boolean bound = BindingGuard.isBound(accounts, ctx.accountId());
        String sort = sortOf(ctx);
        String cursorRaw = ctx.query("cursor", null);
        if (!bound && cursorRaw != null && !cursorRaw.isBlank()) {
            BindingGuard.requireBound(accounts, ctx);
        }
        List<WorkComment> roots = comments.visibleRoots(work.id, sort);
        roots.removeIf(c -> hiddenBetween(c.authorId, ctx.accountId()));
        int limit = bound ? pageLimit(ctx) : PREVIEW_ROOTS;
        int from = bound ? decodeCursor(cursorRaw, sort, work.id, 0) : 0;
        int to = Math.min(from + limit, roots.size());
        List<Object> items = new ArrayList<>();
        for (int i = from; i < to; i++) {
            items.add(rootItem(roots.get(i), work, ctx.accountId(), bound, true));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sort", sort);
        out.put("visibleCommentCount", visibleCount(work.id, ctx.accountId()));
        out.put("items", items);
        out.put("nextCursor", bound && to < roots.size() ? encodeCursor(sort, work.id, to) : null);
        return out;
    }

    private Object listReplies(RequestContext ctx) {
        long me = BindingGuard.requireBound(accounts, ctx);
        WorkComment root = comments.byId(parseId(ctx.path("rootCommentId")));
        if (root == null || !root.root() || !root.publiclyVisible()) {
            throw unavailable();
        }
        visibleWork(ctx, root.workId);
        List<WorkComment> replies = comments.visibleReplies(root.id);
        replies.removeIf(c -> hiddenBetween(c.authorId, me));
        int from = decodeCursor(ctx.query("cursor", null), "latest", root.id, 0);
        int limit = pageLimit(ctx);
        int to = Math.min(from + limit, replies.size());
        List<Object> items = new ArrayList<>();
        for (int i = from; i < to; i++) {
            items.add(commentDto(replies.get(i), root, works.byId(root.workId), me, true));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("nextCursor", to < replies.size() ? encodeCursor("latest", root.id, to) : null);
        return out;
    }

    private Object createRoot(RequestContext ctx) {
        long me = BindingGuard.requireBound(accounts, ctx);
        Work work = visibleWork(ctx, parseId(ctx.path("workId")));
        String body = requireBody(ctx);
        return replayOrSave(me, ctx, "root:" + work.id + ":" + body, () -> {
            WorkComment c = newComment(work.id, me, null, null, body);
            comments.insert(c);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("comment", commentDto(c, c, work, me, true));
            out.put("visibleCommentCount", visibleCount(work.id, me));
            return out;
        });
    }

    private Object createReply(RequestContext ctx) {
        long me = BindingGuard.requireBound(accounts, ctx);
        WorkComment target = comments.byId(parseId(ctx.path("commentId")));
        if (target == null || !target.publiclyVisible()) {
            throw unavailable();
        }
        WorkComment root = target.root() ? target : comments.byId(target.rootCommentId);
        if (root == null || !root.publiclyVisible()) {
            throw unavailable();
        }
        Work work = visibleWork(ctx, target.workId);
        String body = requireBody(ctx);
        return replayOrSave(me, ctx, "reply:" + target.id + ":" + body, () -> {
            WorkComment c = newComment(work.id, me, root.id, target.id, body);
            comments.insert(c);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("comment", commentDto(c, root, work, me, true));
            out.put("rootCommentId", String.valueOf(root.id));
            out.put("replyToCommentId", String.valueOf(target.id));
            out.put("visibleCommentCount", visibleCount(work.id, me));
            return out;
        });
    }

    private Object remove(RequestContext ctx) {
        long me = BindingGuard.requireBound(accounts, ctx);
        WorkComment c = comments.byId(parseId(ctx.path("commentId")));
        if (c == null) {
            throw unavailable();
        }
        Work work = visibleWork(ctx, c.workId);
        boolean owner = work.authorId == me;
        if (c.authorId != me && !owner) {
            throw new ApiException(ApiException.RULE_FORBIDDEN, "这条不能由你来收起。", "comment_forbidden");
        }
        return replayOrSave(me, ctx, "del:" + c.id, () -> {
            int cascaded = 0;
            if (c.root()) {
                cascaded = Math.max(0, comments.cascadeSoftDelete(c, now(), me,
                        owner && c.authorId != me ? "work_owner_moderated" : "author_deleted") - 1);
            } else {
                comments.softDelete(c, now(), me,
                        owner && c.authorId != me ? "work_owner_moderated" : "author_deleted");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("commentId", String.valueOf(c.id));
            out.put("displayState", "hidden");
            out.put("cascadedReplyCount", cascaded);
            out.put("visibleCommentCount", visibleCount(work.id, me));
            return out;
        });
    }

    private Object hide(RequestContext ctx) {
        long me = BindingGuard.requireBound(accounts, ctx);
        Work work = requireOwner(ctx, me);
        WorkComment c = comments.byId(parseId(ctx.path("commentId")));
        requireCommentOnWork(c, work.id);
        int expected = ctx.body() == null ? -1 : ctx.body().has("expectedStateVersion")
                ? ctx.body().get("expectedStateVersion").getAsInt() : -1;
        if (expected >= 0 && expected != c.stateVersion) {
            throw stateConflict(c);
        }
        if (!c.publiclyVisible()) {
            throw unavailable();
        }
        return replayOrSave(me, ctx, "hide:" + c.id + ":" + c.stateVersion, () -> {
            comments.hide(c, now());
            return hideResult(c, work.id, me, WorkComment.OWNER_HIDDEN);
        });
    }

    private Object restore(RequestContext ctx) {
        long me = BindingGuard.requireBound(accounts, ctx);
        Work work = requireOwner(ctx, me);
        WorkComment c = comments.byId(parseId(ctx.path("commentId")));
        requireCommentOnWork(c, work.id);
        if (c.deletedAt != null || !WorkComment.OWNER_HIDDEN.equals(c.displayState)) {
            throw new ApiException(ApiException.RULE_FORBIDDEN, "这条已经不能恢复了。", "comment_unavailable");
        }
        return replayOrSave(me, ctx, "restore:" + c.id + ":" + c.stateVersion, () -> {
            comments.restore(c, now());
            Map<String, Object> out = hideResult(c, work.id, me, WorkComment.VISIBLE);
            out.put("sortRefreshRequired", true);
            return out;
        });
    }

    private Object governance(RequestContext ctx) {
        long me = BindingGuard.requireBound(accounts, ctx);
        Work work = requireOwner(ctx, me);
        List<Object> items = new ArrayList<>();
        for (WorkComment c : comments.ofWork(work.id)) {
            if (WorkComment.OWNER_HIDDEN.equals(c.displayState) && c.deletedAt == null) {
                Map<String, Object> row = commentDto(c, c.root() ? c : comments.byId(c.rootCommentId), work, me, true);
                row.put("canRestore", true);
                items.add(row);
            }
        }
        return Map.of("items", items, "nextCursor", null);
    }

    private Object favorite(RequestContext ctx) {
        long me = BindingGuard.requireBound(accounts, ctx);
        Work work = visibleWork(ctx, parseId(ctx.path("workId")));
        boolean first = !favorites.favorited(me, work.id);
        favorites.put(me, work.id, now());
        if (first && ledger != null) {
            ledger.favoriteChanged(me, work.id, true);
        }
        return Map.of("workId", String.valueOf(work.id), "favorited", true);
    }

    private Object unfavorite(RequestContext ctx) {
        long me = BindingGuard.requireBound(accounts, ctx);
        long workId = parseId(ctx.path("workId"));
        boolean was = favorites.favorited(me, workId);
        favorites.remove(me, workId);
        if (was && ledger != null) {
            ledger.favoriteChanged(me, workId, false);
        }
        return Map.of("workId", String.valueOf(workId), "favorited", false);
    }

    private Object myFavorites(RequestContext ctx) {
        long me = BindingGuard.requireBound(accounts, ctx);
        List<Object> items = new ArrayList<>();
        for (Long workId : favorites.workIdsOf(me)) {
            Work w = works.byId(workId);
            if (w == null || !Work.Status.PUBLIC.equals(w.status) || w.isDeleted()
                    || hiddenBetween(w.authorId, me)) {
                continue;
            }
            items.add(WorkView.listItem(w, storage, false));
        }
        int from = Math.max(0, ctx.queryInt("cursor", 0));
        int limit = pageLimit(ctx);
        int to = Math.min(from + limit, items.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items.subList(from, to));
        out.put("nextCursor", to < items.size() ? String.valueOf(to) : null);
        return out;
    }

    private Map<String, Object> rootItem(WorkComment root, Work work, long viewer, boolean bound, boolean preview) {
        List<WorkComment> replies = comments.visibleReplies(root.id);
        replies.removeIf(c -> hiddenBetween(c.authorId, viewer));
        int visibleReplies = replies.size();
        List<Object> previewReplies = new ArrayList<>();
        int take = Math.min(PREVIEW_REPLIES, visibleReplies);
        for (int i = 0; i < take; i++) {
            previewReplies.add(commentDto(replies.get(i), root, work, viewer, bound));
        }
        int remaining = Math.max(0, visibleReplies - previewReplies.size());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("comment", commentDto(root, root, work, viewer, bound));
        item.put("previewReplies", previewReplies);
        item.put("visibleReplyCount", visibleReplies);
        item.put("remainingReplyCount", remaining);
        item.put("repliesCursor", bound && remaining > 0 ? encodeCursor("latest", root.id, take) : null);
        Map<String, Object> caps = capabilities(root, work, viewer, bound);
        caps.put("canExpandReplies", remaining > 0);
        item.put("capabilities", caps);
        return item;
    }

    private Map<String, Object> commentDto(WorkComment c, WorkComment root, Work work, long viewer, boolean bound) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commentId", String.valueOf(c.id));
        m.put("workId", String.valueOf(c.workId));
        m.put("rootCommentId", c.root() ? null : String.valueOf(c.rootCommentId));
        m.put("replyToCommentId", c.replyToCommentId == null ? null : String.valueOf(c.replyToCommentId));
        m.put("authorPublic", authorPublic(c.authorId));
        m.put("body", c.body);
        m.put("createdAt", c.createdAt);
        m.put("displayState", c.publiclyVisible() ? WorkComment.VISIBLE : "hidden");
        m.put("stateVersion", c.stateVersion);
        if (!c.root() && c.replyToCommentId != null && root != null && c.replyToCommentId != root.id) {
            WorkComment target = comments.byId(c.replyToCommentId);
            if (target == null || !target.publiclyVisible()) {
                m.put("replyToLabel", "一条已删除评论");
            } else {
                m.put("replyToLabel", nickname(target.authorId));
            }
        }
        m.put("capabilities", capabilities(c, work, viewer, bound));
        return m;
    }

    private Map<String, Object> capabilities(WorkComment c, Work work, long viewer, boolean bound) {
        boolean self = c.authorId == viewer;
        boolean owner = work != null && work.authorId == viewer;
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("canReply", bound && c.publiclyVisible());
        caps.put("canDelete", bound && (self || owner) && c.publiclyVisible());
        caps.put("canHide", bound && owner && c.publiclyVisible());
        caps.put("canReport", bound && !self);
        caps.put("canExpandReplies", false);
        caps.put("canRestore", bound && owner && WorkComment.OWNER_HIDDEN.equals(c.displayState));
        return caps;
    }

    private Map<String, Object> authorPublic(long accountId) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("accountId", String.valueOf(accountId));
        a.put("nickname", nickname(accountId));
        return a;
    }

    private String nickname(long accountId) {
        AccountProfile p = accounts.profile(accountId);
        return p == null || p.nickname == null || p.nickname.isBlank() ? "旅人" : p.nickname;
    }

    private Work visibleWork(RequestContext ctx, long workId) {
        Work w = works.byId(workId);
        long viewer = ctx.accountId();
        if (w == null || w.isDeleted()) {
            throw new ApiException(ApiException.NOT_FOUND, "这个作品找不到了。", "work not found");
        }
        if (w.authorId != viewer && (!Work.Status.PUBLIC.equals(w.status) || hiddenBetween(w.authorId, viewer))) {
            throw new ApiException(ApiException.NOT_FOUND, "这个作品找不到了。", "work not visible to viewer");
        }
        return w;
    }

    private Work requireOwner(RequestContext ctx, long me) {
        Work work = visibleWork(ctx, parseId(ctx.path("workId")));
        if (work.authorId != me) {
            throw new ApiException(ApiException.RULE_FORBIDDEN, "只有作者能做这一步。", "comment_forbidden");
        }
        return work;
    }

    private static void requireCommentOnWork(WorkComment c, long workId) {
        if (c == null || c.workId != workId) {
            throw unavailable();
        }
    }

    private WorkComment newComment(long workId, long authorId, Long rootId, Long replyTo, String body) {
        WorkComment c = new WorkComment();
        c.id = ids.nextId();
        c.workId = workId;
        c.authorId = authorId;
        c.rootCommentId = rootId;
        c.replyToCommentId = replyTo;
        c.body = body;
        c.createdAt = now();
        c.updatedAt = c.createdAt;
        return c;
    }

    private String requireBody(RequestContext ctx) {
        String body = Json.getString(ctx.body() == null ? new JsonObject() : ctx.body(), "body", "").strip();
        if (body.isEmpty()) {
            throw new ApiException(ApiException.BAD_PARAM, "先写一句再发出去。", "comment_body_empty");
        }
        if (body.codePointCount(0, body.length()) > MAX_BODY) {
            throw new ApiException(ApiException.BAD_PARAM, "这句有点长了，" + MAX_BODY + " 字以内就好。",
                    "comment_body_too_long");
        }
        return body;
    }

    private Object replayOrSave(long me, RequestContext ctx, String requestHash, java.util.function.Supplier<Map<String, Object>> action) {
        String key = idempotencyKey(ctx);
        if (key.isBlank()) {
            return action.get();
        }
        String slot = me + ":" + key;
        Idempotent existing = idempotency.get(slot);
        if (existing != null) {
            if (!existing.hash.equals(requestHash)) {
                throw new ApiException(ApiException.BAD_PARAM, "这次请求和上次不一样，先刷新再试。",
                        "idempotency_conflict");
            }
            return existing.body;
        }
        Map<String, Object> body = action.get();
        idempotency.put(slot, new Idempotent(requestHash, body));
        return body;
    }

    private static String idempotencyKey(RequestContext ctx) {
        String header = ctx.header("Idempotency-Key");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        if (ctx.body() == null) {
            return "";
        }
        return Json.getString(ctx.body(), "idempotencyKey", "").trim();
    }

    private int visibleCount(long workId, long viewer) {
        int n = 0;
        for (WorkComment c : comments.ofWork(workId)) {
            if (c.publiclyVisible() && !hiddenBetween(c.authorId, viewer)) {
                n++;
            }
        }
        return n;
    }

    private Map<String, Object> hideResult(WorkComment c, long workId, long me, String state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("commentId", String.valueOf(c.id));
        out.put("displayState", state);
        out.put("stateVersion", c.stateVersion);
        out.put("visibleCommentCount", visibleCount(workId, me));
        return out;
    }

    private static ApiException unavailable() {
        return new ApiException(ApiException.NOT_FOUND, "这条内容暂时看不到了。", "comment_unavailable");
    }

    private static ApiException stateConflict(WorkComment c) {
        return new ApiException(ApiException.BAD_PARAM, "先刷新一下再试。", "comment_state_conflict",
                Map.of("commentId", String.valueOf(c.id),
                        "currentDisplayState", c.displayState,
                        "currentStateVersion", c.stateVersion));
    }

    private boolean hiddenBetween(long a, long b) {
        return blocks != null && blocks.hidden(a, b);
    }

    private static String sortOf(RequestContext ctx) {
        String sort = ctx.query("sort", "hot");
        if (!"hot".equals(sort) && !"latest".equals(sort)) {
            throw new ApiException(ApiException.BAD_PARAM, "这一页没能翻过去，回到开头再看看吧。",
                    "comment_sort_version_changed");
        }
        return sort;
    }

    private static int pageLimit(RequestContext ctx) {
        return Math.min(PAGE_MAX, Math.max(1, ctx.queryInt("limit", PAGE_DEFAULT)));
    }

    private static String encodeCursor(String sort, long target, int offset) {
        String raw = RANKING_VERSION + "|" + sort + "|" + target + "|" + offset;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeCursor(String raw, String sort, long target, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|");
            if (parts.length != 4 || Integer.parseInt(parts[0]) != RANKING_VERSION
                    || !sort.equals(parts[1]) || Long.parseLong(parts[2]) != target) {
                throw new ApiException(ApiException.BAD_PARAM, "这一页没能翻过去，回到开头再看看吧。",
                        "comment_cursor_invalid");
            }
            return Integer.parseInt(parts[3]);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ApiException(ApiException.BAD_PARAM, "这一页没能翻过去，回到开头再看看吧。",
                    "comment_cursor_invalid");
        }
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ApiException.BAD_PARAM, "找不到这一条。", "bad id");
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private record Idempotent(String hash, Map<String, Object> body) {
    }
}
