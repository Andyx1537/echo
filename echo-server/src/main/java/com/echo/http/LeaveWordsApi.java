package com.echo.http;

import com.aengine.util.id.IDGenerator;
import com.echo.http.governance.FeatureSwitchService;
import com.echo.http.governance.InteractionPolicy;
import com.echo.http.governance.LeaveWordsStore;
import com.echo.http.model.ModerationModels.MemoryCard;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.safety.OutputSafetyGate;
import com.echo.http.store.EchoStore;
import com.echo.http.store.ModerationStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code C1 留一句话}的 C 端端点（{@code DECISIONS §G⁗⁗⁗‴ S13} · {@code PALETTE §56/§186}）。
 *
 * <table>
 *   <tr><td>{@code GET  /config/flags}</td><td>开关下发，🔴 前端只读</td></tr>
 *   <tr><td>{@code POST /cards/:cardId/messages}</td><td>访客留一句话（最长 60 字）</td></tr>
 *   <tr><td>{@code GET  /cards/:cardId/messages/pending}</td><td>作者侧待处理队列</td></tr>
 *   <tr><td>{@code POST /messages/:messageId/disposition}</td><td>作者三选一处理</td></tr>
 * </table>
 *
 * <h2>为什么挂在 {@code /cards/} 而不是 {@code /windows/}</h2>
 *
 * <p>这两个端点认的是 {@code t_memory_card.id}，而 {@code /windows/} 前缀下的
 * {@code /windows/:petId/flower}、{@code /windows/:petId/remember} 认的是 petId。
 * 曾经两者挂在同一段 URL 下，<b>同一个前缀底下并存两套 id</b>——
 * 🔴 这在「把献花与记得从 petId 迁到 cardId」的迁移期是最容易出错的一处，
 * 因为迁移的两端恰好就是这两套 id。</p>
 *
 * <p>对齐 {@code GovernanceApi} 的 {@code /cards/:id/interaction}：
 * <b>{@code /cards/} 收 cardId，{@code /windows/} 收 petId，没有例外。</b>
 * 趁 C1 还是 P0 默认关、没有线上消费者时搬，代价近乎为零；
 * 拖到发版之后，这个说谎的前缀就再也搬不动了。</p>
 *
 * <h2>🔴 P0 默认关闭，且服务端自己判</h2>
 *
 * <p>前端在开关关闭时根本不呈现入口，但那只是「不呈现」——<b>客户端从来不是权限边界</b>。
 * 本类每一个写入路径都自己再问一次开关（经 {@link InteractionPolicy}，它把
 * 「全局关 &gt; 单条开」的方向写死了）。</p>
 *
 * <h2>🔴 留言者永远看不到处理结果（{@code PALETTE I-05}）</h2>
 *
 * <p>这一条决定了本类几乎所有出参形状：</p>
 * <ul>
 *   <li>{@link #leaveMessage} 回执<b>只有 {@code ok}</b> —— 不回留言 id、不回状态。
 *       只要回执里带上任何可查询的句柄，早晚会有人做一个「我留的话怎么样了」的界面，
 *       那条红线就等于没有了。</li>
 *   <li>被拉黑、被作者关了留言、开关没开、文本被安全闸拦下 —— <b>四种情形回执完全一致</b>，
 *       都是 {@code {ok:true}}。不一致就意味着留言者能把其中任一种探测出来。</li>
 *   <li>{@link #resolveMessage} 三个动作<b>都不派生任何发往留言者的通知</b>。</li>
 * </ul>
 */
@Slf4j
public final class LeaveWordsApi {

    /** 🔴 60 字上限（{@code PALETTE §186}）。也是安全约束：短文本容不下引流话术与诈骗剧本。 */
    public static final int MAX_CHARS = 60;

    /** 作者侧待处理队列一次最多取多少条。 */
    private static final int PENDING_LIMIT = 50;

    private final LeaveWordsStore store;
    private final ModerationStore cards;
    private final EchoStore accounts;
    private final GovernanceApi governance;
    private final FeatureSwitchService switches;
    private final OutputSafetyGate safetyGate;
    private final IDGenerator idGenerator;

    public LeaveWordsApi(LeaveWordsStore store, ModerationStore cards, EchoStore accounts,
                         GovernanceApi governance, FeatureSwitchService switches,
                         OutputSafetyGate safetyGate, IDGenerator idGenerator) {
        this.store = store;
        this.cards = cards;
        this.accounts = accounts;
        this.governance = governance;
        this.switches = switches;
        this.safetyGate = safetyGate;
        this.idGenerator = idGenerator;
    }

    public void register(Router r) {
        // 开关下发：免鉴权。游客首屏也要知道该不该渲染入口，而这份信息本身没有隐私
        r.addPublic("GET", "/config/flags", this::featureFlags);
        r.add("POST", "/cards/:cardId/messages", this::leaveMessage);
        r.add("GET", "/cards/:cardId/messages/pending", this::pendingMessages);
        r.add("POST", "/messages/:messageId/disposition", this::resolveMessage);
    }

    // ==================================================================== 开关

    /**
     * {@code GET /config/flags} —— 服务端功能开关。
     *
     * <p>🔴 <b>只有读，没有写。</b>写开关在后台（需二次审批 + 治理能力全就绪），
     * C 端契约里不该存在任何能改它的形状。</p>
     */
    private Object featureFlags(RequestContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("leaveMessage", switches.isLeaveWordsEnabled());
        return out;
    }

    // ================================================================ 访客留言

    /**
     * {@code POST /cards/:cardId/messages} —— 留一句话。
     *
     * <p>校验顺序是有讲究的：<b>先格式、后闸门</b>。格式错误对所有人一视同仁（不泄漏任何东西），
     * 所以先告诉用户「太长了」比让他以为发出去了要好；闸门相关的一律走静默成功。</p>
     */
    private Object leaveMessage(RequestContext ctx) {
        // 🔴 S1′：写到别人那一屏上，必须已绑定。
        // ⚠️ 这一句今天才真的会拦人——此前 requireBound 只判有没有 token，游客畅通
        long me = requireBound(ctx);
        String text = Json.requireString(ctx.body(), "text").trim();
        if (text.isEmpty()) {
            throw new ApiException(ApiException.BAD_PARAM, "还没写呢，写一句吧。", "empty text");
        }
        // 🔴 按「字符数」判，不是字节数：一个汉字一个字，用户数得出来的那个数
        int chars = text.codePointCount(0, text.length());
        if (chars > MAX_CHARS) {
            throw new ApiException(ApiException.BAD_PARAM,
                    "话有点长了，" + MAX_CHARS + " 字以内就好。",
                    "text too long: " + chars + " > " + MAX_CHARS);
        }

        MemoryCard card = requireCard(parseId(ctx.path("cardId")));

        // 🔴 拉黑 / 作者关了留言 / 全局开关关着 —— 三者合并成同一个静默成功。
        //    分开返回的话，留言者可以对比响应差异把「我被拉黑了」推断出来。
        if (!governance.interactionGuard(card.ownerId, me, InteractionPolicy.LEAVE_WORDS,
                card.interactionJson)) {
            return ok();
        }

        // 🔴 文本安全闸（S13 ② 的「文本安全闸就绪」正是指这一关）。
        //    命中同样静默成功：I-05 定死了绝不显示被拒绝。
        OutputSafetyGate.Verdict verdict = safetyGate.inspectUserText(text);

        LeaveWordsStore.Entry e = new LeaveWordsStore.Entry();
        e.id = idGenerator.nextId();
        e.cardId = card.id;
        e.ownerId = card.ownerId;
        e.actorId = me;
        e.body = text;
        e.disposition = LeaveWordsStore.PENDING;
        // 🔴 命中的照样落库（safetyState=rejected），但它不进任何展示路径：
        //    不删是因为「被闸拦下」与「从来没人留过」对深共鸣率分母是相反的答案。
        e.safetyState = verdict.passed()
                ? LeaveWordsStore.SAFETY_PASSED : LeaveWordsStore.SAFETY_REJECTED;
        e.createdAt = System.currentTimeMillis();

        if (!store.insert(e)) {
            // 一人一卡只留一句。🔴 回执仍是 ok：告诉他「你已经留过了」也是一种可被利用的信号，
            //    而且他本来就无从查询上一句的下场，多这一句话只会引出「那我上次那句呢」。
            log.debug("留言重复提交，未覆盖 cardId={} actorId={}", card.id, me);
            return ok();
        }
        if (!verdict.passed()) {
            log.info("留言被文本安全闸拦下 cardId={} gate={} matched={}",
                    card.id, verdict.gate(), verdict.matched());
        }
        return ok();
    }

    // ================================================================ 作者三选一

    /**
     * {@code GET /cards/:cardId/messages/pending} —— 待我处理的留言。
     *
     * <p>🔴 仅窗主本人可读，<b>非窗主一律空</b>——不报错。报错会把「这张卡有没有待处理留言」
     * 变成一个可探测的信号；而空列表对非窗主来说本来就是事实（没有留言等着<b>他</b>处理）。</p>
     */
    private Object pendingMessages(RequestContext ctx) {
        // 读自己收到的东西，不是 S1′ 射程内的写操作（理由见 requireAuthenticated）
        long me = requireAuthenticated(ctx);
        MemoryCard card = requireCard(parseId(ctx.path("cardId")));
        if (card.ownerId != me) {
            return page(List.of());
        }
        long now = System.currentTimeMillis();
        List<Object> items = new ArrayList<>();
        for (LeaveWordsStore.Entry e : store.pendingOf(me, card.id, PENDING_LIMIT)) {
            items.add(pendingView(e, now));
        }
        return page(items);
    }

    /**
     * {@code POST /messages/:messageId/disposition} —— 收下公开 / 只自己看 / 不留。
     *
     * <p>🔴 三个动作对留言者<b>一视同仁地静默</b>：本方法不产生任何消息、通知或状态回流。
     * 「不留」也不删行（见 {@link LeaveWordsStore}）。</p>
     */
    private Object resolveMessage(RequestContext ctx) {
        // 处置自己收到的留言：是写，但不产生任何公开层信号，也 farm 不出东西
        // （见 requireAuthenticated —— 拦掉会让游客窗主的留言永远处理不了）
        long me = requireAuthenticated(ctx);
        long messageId = parseId(ctx.path("messageId"));
        String disposition = requireDisposition(Json.requireString(ctx.body(), "disposition"));

        LeaveWordsStore.Entry e = store.byId(messageId);
        // 🔴 不区分「不是你的」与「不存在」：否则这个端点会变成一个探测「某条留言存在吗」的工具
        if (e == null || e.ownerId != me) {
            throw new ApiException(ApiException.NOT_FOUND, "这条留言找不到了。",
                    "message not found or not owned: " + messageId);
        }
        if (!store.resolve(messageId, me, disposition, System.currentTimeMillis())) {
            // 已经处理过 —— 幂等回成功。作者重复点一下不该看到报错
            log.debug("留言已处理过，本次不生效 id={}", messageId);
        }
        return ok();
    }

    // ==================================================================== 工具

    private Map<String, Object> pendingView(LeaveWordsStore.Entry e, long now) {
        AccountProfile author = accounts.profile(e.actorId);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", String.valueOf(e.id));
        v.put("authorName", author != null && author.nickname != null ? author.nickname : "旅人");
        v.put("authorAvatar", author != null ? author.avatar : "");
        v.put("text", e.body);
        v.put("time", relativeTime(now - e.createdAt));
        // 契约里 time 是展示串，但前端要自己排序/分组时需要原始时刻，两个都给
        v.put("createdAt", e.createdAt);
        return v;
    }

    /** 相对时间串（契约 {@code PendingMessage.time}）。粒度到天，再往前只说「很久以前」。 */
    private static String relativeTime(long elapsedMs) {
        long minutes = Math.max(0, elapsedMs) / 60_000L;
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        long days = hours / 24;
        return days <= 30 ? days + " 天前" : "很久以前";
    }

    private static Map<String, Object> ok() {
        return Map.of("ok", true);
    }

    /** 统一分页信封 {@code {items,nextCursor}}（与 records/messages 对齐）。 */
    private static Map<String, Object> page(List<Object> items) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        // 待处理队列有 PENDING_LIMIT 上限、量天然很小，本期不分页；键仍在，形状与别处一致
        out.put("nextCursor", null);
        return out;
    }

    private static String requireDisposition(String raw) {
        return switch (raw) {
            case "publish" -> LeaveWordsStore.PUBLIC;
            case "private" -> LeaveWordsStore.PRIVATE;
            case "drop" -> LeaveWordsStore.DECLINED;
            default -> throw new ApiException(ApiException.BAD_PARAM,
                    "收下公开、只自己看、不留——选一个吧。", "invalid disposition: " + raw);
        };
    }

    private MemoryCard requireCard(long cardId) {
        MemoryCard card = cards.card(cardId);
        if (card == null) {
            // 「这张回忆卡」而不是「这扇窗」：本方法查的是 t_memory_card，
            // 端点也已经从 /windows/:cardId 搬到 /cards/:cardId，文案是最后一处没跟上的
            throw new ApiException(ApiException.NOT_FOUND, "这张回忆卡我没有找到，也许它已经被收起来了。",
                    "card not found: " + cardId);
        }
        return card;
    }

    /**
     * {@code S1′}：留一句话是写到<b>别人那一屏上</b>的操作，必须落在可追责主体上。
     *
     * <p>🔴 <b>此前这个方法体是 {@code if (id <= 0) throw}</b>，也就是只判有没有 token；
     * 游客有 token，所以它一次也没拦住过任何人。判据现在在 {@link BindingGuard}。</p>
     */
    private long requireBound(RequestContext ctx) {
        return BindingGuard.requireBound(accounts, ctx);
    }

    /**
     * 只要求有账号 —— 🔴 <b>这不是 {@code S1′}</b>，游客过得去，故意的。
     *
     * <p>用在<b>窗主处理自己收到的留言</b>那两个端点上（{@link #pendingMessages} 读、
     * {@link #resolveMessage} 写）。⚠️ 那两处不能用 {@link #requireBound}：
     * 🔴 <b>游客可以建档、可以有一扇窗</b>（建档路径上没有绑定守卫），
     * 所以游客窗主会真的收到留言；把这两处拦掉，他收到的留言就<b>永远处置不了</b>，
     * 而「收下 / 只自己看 / 不留」是留言功能唯一的用户控制手段。</p>
     *
     * <p>📌 判据：{@code S1′} 拦的是「产生公开层信号」的写操作（理由逐字是暖光可被 farm）。
     * <b>处置自己收到的东西既不产生公开信号、也 farm 不出任何东西</b> —— 得先有人给你留言。</p>
     */
    private static long requireAuthenticated(RequestContext ctx) {
        return BindingGuard.requireAuthenticated(ctx);
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
