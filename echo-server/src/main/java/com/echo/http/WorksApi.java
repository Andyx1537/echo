package com.echo.http;

import com.echo.http.governance.BlockService;
import com.echo.http.safety.OutputSafetyGate;
import com.echo.http.store.EchoStore;
import com.echo.http.work.Work;
import com.echo.http.work.WorkStore;
import com.echo.http.work.WorkView;
import com.echo.infra.storage.IStorage;
import com.aengine.util.id.IDGenerator;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作品域端点：发布、瀑布、个人作品页、详情、删除。
 *
 * <h2>这个类补的是主线第 10 步</h2>
 *
 * <p>`PRODUCT-IMPLEMENTATION-AUDIT §0` 记录过：审核、分发、曝光整条下游都已建成，
 * 但<b>服务端没有任何创建可发布内容的入口</b>，唯一能造卡的是只在测试里调用的
 * {@code ModerationStore.putCard()}。本类是那个缺失的入口。</p>
 *
 * <h2>🔴 发布不等于公开</h2>
 *
 * <p>{@code POST /works} 落库时 {@code status=pending}，<b>不是</b> {@code public}。
 * {@code DECISIONS OM3} 定死了「生成 / 发布 / 过审是三个时刻，不得合并」——
 * 合并之后，作者按下发布的那一刻内容就已经在广场上，审核变成事后补救，
 * 而事后补救意味着<b>总有一段时间违规内容是可见的</b>。</p>
 *
 * <p>所以发布接口的成功回执是「已提交」而不是「已发布」，文案上也不要写成后者。</p>
 */
@Slf4j
public final class WorksApi {

    /** 标题字数上限，与 {@code t_memory_card.title} 同口径。 */
    private static final int MAX_TITLE_CHARS = 30;
    /** 正文字数上限。 */
    private static final int MAX_BODY_CHARS = 500;
    /** 单页最多给多少条。 */
    private static final int PAGE_MAX = 20;
    /** 视频时长上限（毫秒）。超过这个长度的不收。 */
    private static final int MAX_DURATION_MS = 5 * 60 * 1000;

    private final WorkStore store;
    private final EchoStore accounts;
    private final IStorage storage;
    /** 素材归属。发布时据此判 mediaKey / posterKey 是不是本人上传的。 */
    private final com.echo.http.work.ResourceStore resources;
    private final IDGenerator idGenerator;
    private final OutputSafetyGate safetyGate;
    private BlockService blockService;
    /** 卡归属。从回忆卡发布时判这张卡是不是本人的。 */
    private com.echo.http.store.ModerationStore cards;

    public WorksApi(WorkStore store, EchoStore accounts, IStorage storage,
                    com.echo.http.work.ResourceStore resources,
                    OutputSafetyGate safetyGate, IDGenerator idGenerator) {
        this.store = store;
        this.accounts = accounts;
        this.storage = storage;
        this.resources = resources;
        this.safetyGate = safetyGate;
        this.idGenerator = idGenerator;
    }

    public void setBlockService(BlockService blockService) {
        this.blockService = blockService;
    }

    public void setCardStore(com.echo.http.store.ModerationStore cards) {
        this.cards = cards;
    }

    public void register(Router r) {
        r.add("POST", "/works", this::publish);
        r.add("GET", "/works", this::feed);
        r.add("GET", "/works/:workId", this::detail);
        r.add("GET", "/users/:userId/works", this::worksOfUser);
        r.add("DELETE", "/works/:workId", this::remove);
    }

    // ============================================================== 发布

    /**
     * {@code POST /works} —— 发布作品。
     *
     * <p>入参：{@code mediaType}（image|video）、{@code mediaKey}（{@code POST /upload}
     * 返回的 resourceId）、{@code posterKey}（视频必填）、{@code durationMs}、
     * {@code width}、{@code height}、{@code title}、{@code body}、{@code visibility}、
     * {@code sourceCardId}（可选，从回忆卡发布时带上）、{@code aiGenerated}。</p>
     */
    private Object publish(RequestContext ctx) {
        // 🔴 S1′：把内容放到公共空间，必须已绑定。游客是无限身份，不绑定等于零成本灌站
        long me = BindingGuard.requireBound(accounts, ctx);
        JsonObject b = ctx.body();

        String mediaType = Json.getString(b, "mediaType", Work.MediaType.IMAGE);
        if (!Work.MediaType.IMAGE.equals(mediaType) && !Work.MediaType.VIDEO.equals(mediaType)) {
            throw new ApiException(ApiException.BAD_PARAM, "这种素材还支持不了呢。",
                    "unsupported mediaType: " + mediaType);
        }
        String mediaKey = Json.requireString(b, "mediaKey").trim();
        if (mediaKey.isEmpty()) {
            throw new ApiException(ApiException.BAD_PARAM, "还没选素材呢。", "empty mediaKey");
        }

        // 🔴 素材必须是本人上传的。此前只判非空，于是拿到别人的 key 就能把别人的
        //    照片发布成自己的作品（SPEC-security §4.14 E4）。key 本身猜不出来，
        //    但不需要猜——作品瀑布把完整直链下发给任何持游客 token 的人（同上 E5）。
        //    统一回 400 而不区分「不存在 / 不是你的」，区分开来等于确认这个 key 存在。
        if (!resources.ownedBy(mediaKey, me)) {
            throw new ApiException(ApiException.BAD_PARAM, "这份素材找不到了，重新选一次好吗？",
                    "mediaKey not owned by " + me);
        }

        boolean video = Work.MediaType.VIDEO.equals(mediaType);
        String posterKey = Json.getString(b, "posterKey", "").trim();
        // 🔴 与 t_work_ck_video_poster 同一条规则，在这里挡是为了给出人话，
        //    落到 DB 约束上只会是一个 500。两处都要有，不要因为"DB 会挡"就省掉这里
        if (video && posterKey.isEmpty()) {
            throw new ApiException(ApiException.BAD_PARAM, "视频还没生成封面，稍等一下再发？",
                    "video requires posterKey");
        }
        // 首帧也要判归属，否则封面这条路径就是 E4 的一个漏口。
        if (video && !resources.ownedBy(posterKey, me)) {
            throw new ApiException(ApiException.BAD_PARAM, "封面对不上，重新生成一下？",
                    "posterKey not owned by " + me);
        }
        int durationMs = Json.getInt(b, "durationMs", 0);
        if (video && durationMs > MAX_DURATION_MS) {
            throw new ApiException(ApiException.BAD_PARAM,
                    "视频有点长了，" + (MAX_DURATION_MS / 60000) + " 分钟以内就好。",
                    "duration too long: " + durationMs);
        }

        String title = Json.getString(b, "title", "").trim();
        requireWithin(title, MAX_TITLE_CHARS, "标题");
        String body = Json.getString(b, "body", "").trim();
        requireWithin(body, MAX_BODY_CHARS, "正文");

        String visibility = Json.getString(b, "visibility", "public");
        if (!List.of("private", "friends", "public").contains(visibility)) {
            throw new ApiException(ApiException.BAD_PARAM, "可见范围不太对。",
                    "bad visibility: " + visibility);
        }

        Long sourceCardId = parseNullableId(Json.getString(b, "sourceCardId", ""));
        // 🔴 判重之前先判归属。publishedFromCard 只回答「这张卡发过没有」，
        //    它<b>不</b>回答「这张卡是不是你的」——两个问题长得像，答错一个
        //    就等于允许把别人的回忆卡发成自己的作品（SPEC-security §4.14 E4 相关项）。
        if (sourceCardId != null) {
            if (cards == null) {
                // 没装配就不许走这条路：静默放行等于校验形同虚设。
                throw new ApiException(ApiException.SERVER_ERROR, "现在发不了，稍后再试？",
                        "card store not wired");
            }
            var card = cards.card(sourceCardId);
            if (card == null || card.ownerId != me) {
                throw new ApiException(ApiException.BAD_PARAM, "这张卡找不到了。",
                        "card " + sourceCardId + " not owned by " + me);
            }
        }
        if (sourceCardId != null && store.publishedFromCard(sourceCardId)) {
            // 🔴 这一条要给明确回执，不能静默成功：作者以为没发出去会再发一次，
            //    而他看不到自己已经发过的那一条（它还在审核里）
            throw new ApiException(ApiException.BAD_PARAM, "这张卡已经发布过啦。",
                    "duplicate publish from card " + sourceCardId);
        }

        long now = System.currentTimeMillis();
        Work w = new Work();
        w.id = idGenerator.nextId();
        w.authorId = me;
        w.sourceCardId = sourceCardId;
        w.mediaType = mediaType;
        w.mediaKey = mediaKey;
        w.posterKey = video ? posterKey : "";
        w.durationMs = video ? durationMs : 0;
        w.width = Math.max(0, Json.getInt(b, "width", 0));
        w.height = Math.max(0, Json.getInt(b, "height", 0));
        w.title = title;
        w.body = body;
        w.topicIdsJson = "[]";
        w.visibility = visibility;
        // 🔴 pending 不是 public：见类注释的 OM3
        w.status = Work.Status.PENDING;
        w.originType = Work.OriginType.USER;
        w.aiGenerated = Json.getBool(b, "aiGenerated", false);
        w.createdAt = now;
        w.updatedAt = now;
        w.publishedAt = now;

        // 文本安全闸。🔴 命中不是静默成功——发布是作者的主动创作，
        //    悄悄吞掉会让他对着一个永远不出现的作品干等
        if (safetyGate != null) {
            OutputSafetyGate.Verdict verdict = safetyGate.inspectUserText(title + "\n" + body);
            if (!verdict.passed()) {
                throw new ApiException(ApiException.BAD_PARAM,
                        "这段话里有些词不太合适，改一改再发？", "safety gate rejected");
            }
        }

        if (!store.insert(w)) {
            throw new ApiException(ApiException.BAD_PARAM, "没能发出去，再试一次？",
                    "insert failed for work " + w.id);
        }
        log.info("[works] 发布 id={} authorId={} mediaType={} fromCard={} ai={}",
                w.id, me, mediaType, sourceCardId, w.aiGenerated);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("work", WorkView.detail(w, storage, true));
        // 🔴 文案是「已提交」不是「已发布」：审核还没过，广场上还看不到它
        out.put("message", "已提交，过一会儿就能在广场看到它了。");
        return out;
    }

    // ============================================================== 读

    /** {@code GET /works} —— 作品瀑布。 */
    private Object feed(RequestContext ctx) {
        long viewer = ctx.accountId();
        List<Object> items = new ArrayList<>();
        // 多取一些再过滤：拉黑要在应用层判，SQL 层拿不到
        for (Work w : store.publicWorks(PAGE_MAX * 5)) {
            if (hiddenBetween(w.authorId, viewer)) {
                continue;
            }
            items.add(WorkView.listItem(w, storage, false));
        }
        return page(items, ctx);
    }

    /** {@code GET /users/:userId/works} —— 个人作品页。 */
    private Object worksOfUser(RequestContext ctx) {
        long viewer = ctx.accountId();
        long target = parseId(ctx.path("userId"));
        boolean self = viewer == target;
        if (!self && hiddenBetween(target, viewer)) {
            // 🔴 拉黑后返回空列表而不是 403：403 等于确认"这个人存在且拉黑了你"
            return page(List.of(), ctx);
        }
        List<Object> items = new ArrayList<>();
        for (Work w : store.worksOfAuthor(target, self, PAGE_MAX * 5)) {
            items.add(WorkView.listItem(w, storage, self));
        }
        return page(items, ctx);
    }

    /** {@code GET /works/:workId} —— 作品详情。 */
    private Object detail(RequestContext ctx) {
        long viewer = ctx.accountId();
        Work w = store.byId(parseId(ctx.path("workId")));
        if (w == null) {
            throw new ApiException(ApiException.NOT_FOUND, "这个作品找不到了。", "work not found");
        }
        boolean self = w.authorId == viewer;
        if (!self) {
            if (!Work.Status.PUBLIC.equals(w.status) || hiddenBetween(w.authorId, viewer)) {
                // 🔴 与"不存在"同一个回执：区分开来等于泄漏"它存在但你看不了"
                throw new ApiException(ApiException.NOT_FOUND, "这个作品找不到了。",
                        "work not visible to viewer");
            }
        }
        return Map.of("work", WorkView.detail(w, storage, self));
    }

    /** {@code DELETE /works/:workId} —— 作者删除自己的作品（软删）。 */
    private Object remove(RequestContext ctx) {
        long me = ctx.accountId();
        long id = parseId(ctx.path("workId"));
        Work w = store.byId(id);
        if (w == null || w.authorId != me) {
            // 不区分"不存在"与"不是你的"，理由同 detail
            throw new ApiException(ApiException.NOT_FOUND, "这个作品找不到了。",
                    "work not found or not owned");
        }
        store.softDelete(id, me, "author_delete", System.currentTimeMillis());
        return Map.of("ok", true);
    }

    // ============================================================== 内部

    private boolean hiddenBetween(long a, long b) {
        return blockService != null && blockService.hidden(a, b);
    }

    private Map<String, Object> page(List<Object> items, RequestContext ctx) {
        int cursor = Math.max(0, ctx.queryInt("cursor", 0));
        int limit = Math.min(PAGE_MAX, Math.max(1, ctx.queryInt("limit", PAGE_MAX)));
        int from = Math.min(cursor, items.size());
        int to = Math.min(from + limit, items.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items.subList(from, to));
        out.put("nextCursor", to < items.size() ? String.valueOf(to) : null);
        return out;
    }

    /** 按字符数校验，不是字节数：一个汉字一个字，用户数得出来的那个数。 */
    private static void requireWithin(String s, int max, String what) {
        if (s == null || s.isEmpty()) {
            return;
        }
        int chars = s.codePointCount(0, s.length());
        if (chars > max) {
            throw new ApiException(ApiException.BAD_PARAM,
                    what + "有点长了，" + max + " 字以内就好。",
                    what + " too long: " + chars + " > " + max);
        }
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ApiException.BAD_PARAM, "参数不太对。", "bad id: " + raw);
        }
    }

    private static Long parseNullableId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parseId(raw.trim());
    }
}
