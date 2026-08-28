package com.echo.http;

import com.echo.http.governance.BlockService;
import com.echo.http.governance.FeatureSwitchService;
import com.echo.http.governance.InteractionPolicy;
import com.echo.http.governance.ReportService;
import com.echo.http.model.ModerationModels.MemoryCard;
import com.echo.http.store.ModerationStore;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code S3} 三项治理能力的 C 端端点：举报 / 拉黑 / 关互动。
 *
 * <p>这三项是 {@code S13} 留一句话开关的开启前置。端点挂上之后，
 * {@code CapabilityRegistry} 的对应探针才会探到"能力在位"。</p>
 *
 * <p>🔴 三条硬约束的落点：</p>
 * <table>
 *   <tr><td>举报人身份不可见</td>
 *       <td>出参用 {@link ReportService.ReporterView}，该 record 没有 reporterId 字段</td></tr>
 *   <tr><td>拉黑不可感知 + 静默失效</td>
 *       <td>{@link #interactionGuard} 在被拦时返回<b>成功形状</b>的响应，不抛错、不通知</td></tr>
 *   <tr><td>全局关 &gt; 单条开</td>
 *       <td>{@link InteractionPolicy#enabled} 先判全局再判单条，单条只能关</td></tr>
 * </table>
 */
@Slf4j
public final class GovernanceApi {

    private final ReportService reports;
    private final BlockService blocks;
    private final InteractionPolicy interactions;
    private final FeatureSwitchService switches;
    private final ModerationStore cards;

    public GovernanceApi(ReportService reports, BlockService blocks,
                         InteractionPolicy interactions, FeatureSwitchService switches,
                         ModerationStore cards) {
        this.reports = reports;
        this.blocks = blocks;
        this.interactions = interactions;
        this.switches = switches;
        this.cards = cards;
    }

    public void register(Router r) {
        // ---- 举报 ----
        r.add("POST", "/reports", this::submitReport);
        r.add("GET", "/reports/mine", this::myReports);
        r.add("GET", "/reports/reasons", this::reportReasons);
        // ---- 拉黑 ----
        r.add("POST", "/accounts/:id/block", this::block);
        r.add("DELETE", "/accounts/:id/block", this::unblock);
        r.add("GET", "/accounts/blocked", this::blockedList);
        // ---- 关互动 ----
        r.add("PATCH", "/cards/:id/interaction", this::patchInteraction);
        r.add("GET", "/cards/:id/interaction", this::getInteraction);
    }

    // ==================================================================== 举报

    /** {@code POST /reports} —— 举报回忆卡 / 留言 / 账号。 */
    private Object submitReport(RequestContext ctx) {
        long me = requireAuthenticated(ctx);
        JsonObject b = ctx.body();
        String targetType = Json.getString(b, "targetType", "");
        long targetId = parseId(Json.getString(b, "targetId", "0"));
        String reasonCode = Json.getString(b, "reasonCode", "other");
        String note = Json.getString(b, "note", null);

        ReportService.ReporterView v = reports.submit(me, targetType, targetId, reasonCode, note);
        return reporterView(v);
    }

    /** {@code GET /reports/mine} —— 我提交过的举报。🔴 不含处置结果。 */
    private Object myReports(RequestContext ctx) {
        long me = requireAuthenticated(ctx);
        var list = reports.mine(me, 50).stream().map(GovernanceApi::reporterView).toList();
        return Map.of("items", list);
    }

    /**
     * {@code GET /reports/reasons} —— 理由码字典。
     *
     * <p>⚠️ 出参带 {@code provisional:true}。🔴 这不是装饰：理由码字典在规格里<b>还没有</b>
     * （{@code API-CONTRACT §17.6} 明确本轮不代拟），前端与运营都需要知道这套码会变，
     * 不要拿它建长期报表。这是「保留关位 + 诚实返回未就绪」范式在举报域的落点。</p>
     */
    private Object reportReasons(RequestContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", ReportService.PROVISIONAL_REASONS.stream().sorted().toList());
        out.put("provisional", ReportService.REASON_DICT_PROVISIONAL);
        out.put("note", "理由码字典尚未定稿，分类维度可能变化");
        return out;
    }

    private static Map<String, Object> reporterView(ReportService.ReporterView v) {
        // 🔴 逐字段显式拷贝，不做反射序列化——将来给 ReporterView 加字段时，
        //    这里不会自动把新字段泄漏到 C 端。
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(v.id()));
        m.put("targetType", v.targetType());
        m.put("state", v.state());
        m.put("feedback", v.feedback());
        m.put("createdAt", v.createdAt());
        return m;
    }

    // ==================================================================== 拉黑

    /** {@code POST /accounts/:id/block} —— 单向拉黑。🔴 不通知对方。 */
    private Object block(RequestContext ctx) {
        long me = requireAuthenticated(ctx);
        long peer = parseId(ctx.path("id"));
        blocks.block(me, peer);
        // 幂等：无论本次是否真的新建，出参一致（对调用方来说结果就是"已拉黑"）
        return Map.of("blocked", true);
    }

    /** {@code DELETE /accounts/:id/block} —— 解除拉黑。🔴 同样不通知对方。 */
    private Object unblock(RequestContext ctx) {
        long me = requireAuthenticated(ctx);
        long peer = parseId(ctx.path("id"));
        blocks.unblock(me, peer);
        return Map.of("blocked", false);
    }

    /** {@code GET /accounts/blocked} —— 我拉黑了谁。🔴 只有本人能看。 */
    private Object blockedList(RequestContext ctx) {
        long me = requireAuthenticated(ctx);
        return Map.of("items", blocks.store().blockedList(me).stream()
                .map(String::valueOf).toList());
    }

    // ================================================================== 关互动

    /**
     * {@code PATCH /cards/:id/interaction} —— 作者关闭/开启自己单条内容的某类互动。
     *
     * <p>🔴 出参回的是<b>生效值</b>（{@code effective}）而不是作者的原始意图：
     * 作者把 {@code leaveWords} 设成 true 时，若全局开关关着，回的仍是 false。
     * 让作者立刻看到"这个我打不开"，比让他以为打开了、然后发现没人能留言要好。</p>
     */
    private Object patchInteraction(RequestContext ctx) {
        long me = requireAuthenticated(ctx);
        long cardId = parseId(ctx.path("id"));
        MemoryCard card = requireOwnCard(cardId, me);

        JsonObject b = ctx.body();
        Map<String, Boolean> patch = new LinkedHashMap<>();
        for (String key : InteractionPolicy.KEYS) {
            if (b.has(key) && !b.get(key).isJsonNull()) {
                patch.put(key, b.get(key).getAsBoolean());
            }
        }
        if (patch.isEmpty()) {
            throw new ApiException(ApiException.BAD_PARAM, "没有要改的设置。",
                    "no recognised interaction key in body");
        }

        String merged = interactions.merge(card.interactionJson, patch);
        long now = System.currentTimeMillis();
        // 🔴 用窄 UPDATE，不用 putCard —— 后者是 ON CONFLICT DO NOTHING 的造数入口，
        //    对已存在的卡是空操作，开关会看起来改成功而实际没改。
        if (!cards.updateInteraction(cardId, merged, now)) {
            throw new ApiException(ApiException.NOT_FOUND, "这张回忆卡找不到了。",
                    "card not found or deleted on update: " + cardId);
        }
        card.interactionJson = merged;
        card.updatedAt = now;
        log.info("作者更新互动开关 cardId={} patch={}", cardId, patch);
        return interactionView(card);
    }

    /** {@code GET /cards/:id/interaction} —— 该卡各类互动的生效状态（前端据此隐藏入口）。 */
    private Object getInteraction(RequestContext ctx) {
        long cardId = parseId(ctx.path("id"));
        MemoryCard card = cards.card(cardId);
        if (card == null) {
            throw new ApiException(ApiException.NOT_FOUND, "这张回忆卡找不到了。",
                    "card not found: " + cardId);
        }
        return interactionView(card);
    }

    private Map<String, Object> interactionView(MemoryCard card) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cardId", String.valueOf(card.id));
        out.put("effective", interactions.effective(card.interactionJson));
        // 作者的原始意图单独回，便于前端区分"我关的"与"全局关的"
        Map<String, Boolean> authored = new LinkedHashMap<>();
        for (String k : InteractionPolicy.KEYS) {
            authored.put(k, interactions.authorAllows(k, card.interactionJson));
        }
        out.put("authored", authored);
        out.put("leaveWordsGlobalOn", switches.isLeaveWordsEnabled());
        return out;
    }

    // ============================================================ 静默失效闸

    /**
     * 互动前置校验：🔴 <b>返回 false 表示"假装成功、实际不生效"</b>。
     *
     * <p>调用方必须这样用：</p>
     * <pre>
     *   if (!governance.interactionGuard(ownerId, actorId, key, interactionJson)) {
     *       return successShapedResponse();   // 🔴 不抛错、不落库、不通知
     *   }
     * </pre>
     *
     * <p>两种被拦情形都走这条路，且<b>对调用方不可区分</b>：</p>
     * <ol>
     *   <li>内容主人拉黑了发起人 —— 若报错，被拉黑方立刻知道是谁拉黑了他，
     *       一次拉黑就变成一次冲突升级（见 {@link BlockService}）。</li>
     *   <li>该类互动被关闭 —— 这一类<b>可以</b>让前端提前知道
     *       （{@code GET /cards/:id/interaction}），所以正常路径下用户根本看不到入口；
     *       走到这里说明是绕过 UI 的请求。</li>
     * </ol>
     *
     * <p>⚠️ 两种情形<b>合并成同一个返回值</b>是刻意的：若分开返回，
     * 被拉黑方可以对比"这张卡关了互动"与"我被拉黑了"两种响应差异，把拉黑探测出来。</p>
     */
    public boolean interactionGuard(long ownerId, long actorId, String interactionKey,
                                    String interactionJson) {
        if (!blocks.canInteract(ownerId, actorId)) {
            // 🔴 只记服务端日志，不产生任何面向 actor 的信号
            log.debug("互动被拉黑拦下（静默失效）owner={} actor={}", ownerId, actorId);
            return false;
        }
        if (interactionKey != null && !interactions.enabled(interactionKey, interactionJson)) {
            log.debug("互动被作者/全局开关拦下 owner={} key={}", ownerId, interactionKey);
            return false;
        }
        return true;
    }

    // ==================================================================== 工具

    /**
     * 只要求有账号 —— 🔴 <b>本类六个端点<u>全部</u>不适用 {@code S1′}，游客过得去，故意的。</b>
     *
     * <h2>🔴 这里此前叫 {@code requireBound}，注释逐字引着 {@code S1′}</h2>
     *
     * <p>那个名字与那条注释都是错的，而且是两层错：<b>实现</b>只判 {@code id <= 0}（有没有 token，
     * 游客有），<b>位置</b>也不对 —— {@code S1′} 逐字点名的受限写操作是
     * <b>建档 / 上传素材 / 触发 AI 生成 / 记得 / 献花 / 发布到公开层 / 导出</b>七项，
     * <b>举报、拉黑、关互动一项都不在里面</b>。</p>
     *
     * <h2>为什么这三类<u>不能</u>要求绑定</h2>
     *
     * <ul>
     *   <li>🔴 <b>举报</b>：举报通道对所有能看到内容的人畅通是治理前提（{@code S3} 把它列为
     *       UGC 上线五项前置之一）。⚠️ 要求先绑手机号才能举报，等于让「看到了违规内容」
     *       与「愿意交出手机号」绑在一起，而前者随时发生、后者是一道决心门槛。</li>
     *   <li>🔴 <b>拉黑 / 解除拉黑</b>：这是<b>自我保护</b>动作。把它拦掉是从被骚扰的人手里
     *       收走唯一的自保手段 —— 收紧安全约束不该以降低安全为代价。</li>
     *   <li>🔴 <b>关互动</b>：作者关掉自己卡上的留言/献花。游客可以建档发卡（那条路上没有绑定守卫），
     *       所以游客作者是真实存在的；拦掉他就关不了自己卡上的互动，⚠️ <b>方向与安全约束相反</b>。</li>
     *   <li><b>三个 GET</b>（{@code /reports/mine} · {@code /accounts/blocked} · {@code /cards/:id/interaction}）
     *       是读，而 {@code S1′} 第一句逐字是「<b>纯游客只读</b>」—— 读是它明确放开的。</li>
     * </ul>
     *
     * <p>📌 判据统一成一句：{@code S1′} 的裁定理由是<b>游客是无限身份，暖光可被零成本 farm，
     * 污染公开层信号</b>。本类六个端点<b>一个都不产生公开层信号</b>，farm 它们也拿不到任何东西。</p>
     *
     * <p>⚠️ 🔴 <b>如果将来要把某一处改成必须绑定，改的是这一行的调用，不是这个方法</b> ——
     * 真绑定判定在 {@link BindingGuard#requireBound}，本类没有装配账号存储正是因为今天用不上它。</p>
     */
    private static long requireAuthenticated(RequestContext ctx) {
        return BindingGuard.requireAuthenticated(ctx);
    }

    private MemoryCard requireOwnCard(long cardId, long me) {
        MemoryCard card = cards.card(cardId);
        if (card == null) {
            throw new ApiException(ApiException.NOT_FOUND, "这张回忆卡找不到了。",
                    "card not found: " + cardId);
        }
        if (card.ownerId != me) {
            // 🔴 不区分"不是你的"与"不存在"，避免成为归属探测工具
            throw new ApiException(ApiException.NOT_FOUND, "这张回忆卡找不到了。",
                    "card " + cardId + " not owned by " + me);
        }
        return card;
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (RuntimeException e) {
            throw new ApiException(ApiException.BAD_PARAM, "这个不太对，换个方式试试。",
                    "invalid id: " + raw);
        }
    }
}
