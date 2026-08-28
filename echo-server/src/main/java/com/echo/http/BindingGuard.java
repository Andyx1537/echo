package com.echo.http;

import com.echo.http.model.Models.AccountProfile;
import com.echo.http.store.EchoStore;

/**
 * {@code S1′} 的绑定态判定 —— 🔴 <b>全仓唯一一处</b>。
 *
 * <h2>判据是哪个，以及为什么不是别的</h2>
 *
 * <p>判据是 {@link AccountProfile#guest}。它是全仓<b>唯一</b>记录「绑过没绑过」的地方：
 * {@code POST /auth/bind} 通过时只写这一个字段（{@code profile.guest = false}，
 * token 不变、账号不换），落库为 {@code t_account_profile.guest smallint DEFAULT 1}。</p>
 *
 * <p>⚠️ <b>{@code t_account.openId} 不是判据</b>，尽管它看起来像登录态：
 * {@code EchoApi.allocateAccountId} 建游客号时把 <b>deviceId 填进了 openId</b>，
 * 所以绑没绑它都有值。⚠️ <b>{@code RequestContext.accountId()} 也不是判据</b>，
 * 理由见 {@link #requireAuthenticated}。</p>
 *
 * <h2>🔴 为什么 {@code accountId <= 0} 不能当绑定判据</h2>
 *
 * <p>这个错误在本仓活过三天，形态是<b>注释引着 {@code S1′}、实现判着有没有 token</b>：
 * 三个类里各有一份 {@code if (id <= 0) throw}，注释逐字写「{@code S1′}：一切写操作都需要
 * 可验证的身份绑定」。而 {@code /auth/guest} <b>给游客发 token</b>，游客的 accountId 是正数，
 * 于是十二处调用点<b>全部放行</b>。</p>
 *
 * <p>🔴 <b>它比没有守卫更坏</b>：来审「{@code S1′} 落地了吗」的人搜到守卫名、看到注释里的编号，
 * 就打勾走了。{@code id <= 0} 该待的地方是 {@link #requireAuthenticated}，那里它是对的。</p>
 *
 * <h2>两个方法的分工（🔴 不要混用）</h2>
 *
 * <table>
 *   <tr><td>{@link #requireBound}</td>
 *       <td>🔴 <b>这是 {@code S1′}</b>。游客过不去。用在 {@code S1′} 点名的写操作上</td></tr>
 *   <tr><td>{@link #requireAuthenticated}</td>
 *       <td>只要求「有一个已鉴权账号」，<b>游客算过</b>。⚠️ 这<b>不是</b> {@code S1′}，
 *           用它的地方注释必须写明为什么 {@code S1′} 不适用</td></tr>
 * </table>
 */
public final class BindingGuard {

    /**
     * 兜底文案：{@code S1′} 拦下时的默认那一句。
     *
     * <p>📌 用在<b>产品还没有专门给过文案</b>的拦截点上（建档 / 上传素材 / 触发 AI 生成 /
     * 发布到公开层）。⚠️ 它只说了「要去绑」，没说「为什么这一步值得」——
     * 🔴 <b>建档那一处最需要一句专门的文案</b>，那是转化损失最大的一处。</p>
     */
    public static final String COPY_DEFAULT = "先绑定一下手机号吧。";

    /**
     * 「记得」被拦时的那一句 —— 🔴 <b>产品负责人 2026-08-27 定，逐字，不许改。</b>
     */
    public static final String COPY_REMEMBER = "这一步要先绑手机号。绑完你留下的每一次记得都还在。";

    /**
     * 「献花」被拦时的那一句。
     *
     * <p>📌 与 {@link #COPY_REMEMBER} 的唯一差别是把「记得」换成「心意」——
     * 献花这条路上「你留下的每一次记得」不通顺，而产品的授权正是<b>只许换这两个字</b>。
     * 「心意」也是献花在 {@code D8}/{@code FL1} 里的正式说法（花是给它的一份心意）。</p>
     */
    public static final String COPY_FLOWER = "这一步要先绑手机号。绑完你留下的每一次心意都还在。";

    private BindingGuard() {
    }

    /**
     * 这个账号完成过可验证绑定吗。
     *
     * <p>🔴 <b>profile 查不到一律按未绑定处理</b>：「不知道绑没绑」和「绑了」不是一回事，
     * 而这道守卫的默认值必须站在收紧那一侧。同理 {@code accounts == null}（没装配账号存储）
     * 也返回 false —— 装配漏了应当立刻被撞出来，不该静默变成全部放行。</p>
     */
    public static boolean isBound(EchoStore accounts, long accountId) {
        if (accounts == null || accountId <= 0) {
            return false;
        }
        AccountProfile profile = accounts.profile(accountId);
        return profile != null && !profile.guest;
    }

    /**
     * {@code S1′}：这个写操作必须落在<b>可追责主体</b>上，游客不行。
     *
     * <p>裁定理由逐字：<b>游客是无限身份，写操作对游客开放即对脚本开放</b>；
     * 具体到记得与献花，是<b>暖光可被零成本 farm，污染公开层信号</b>。</p>
     *
     * <p>📌 回 {@link ApiException#BINDING_REQUIRED}（1002 / HTTP 403），<b>不是 1001</b>——
     * 前端必须能把「要去绑定」和「token 过期了」分开处置，理由见那个常量的注释。</p>
     *
     * <p>用 {@link #COPY_DEFAULT} 那一句。产品给过专门文案的拦截点走
     * {@link #requireBound(EchoStore, RequestContext, String)}。</p>
     */
    public static long requireBound(EchoStore accounts, RequestContext ctx) {
        return requireBound(accounts, ctx, COPY_DEFAULT);
    }

    /**
     * 同 {@link #requireBound(EchoStore, RequestContext)}，但用指定的那一句文案。
     *
     * <p>🔴 <b>{@code userMessage} 只许传本类的 {@code COPY_*} 常量</b>，不要在调用点写字面量：
     * {@code S1′} 的拒绝文案全部集中在本类，才能一眼看完「被拦的人会看到什么」，
     * 也才守得住「这条红线的拒绝只有一个产地」那条结构检查。</p>
     */
    public static long requireBound(EchoStore accounts, RequestContext ctx, String userMessage) {
        long id = ctx.accountId();
        if (!isBound(accounts, id)) {
            throw new ApiException(ApiException.BINDING_REQUIRED, userMessage,
                    detail(accounts, id, null));
        }
        return id;
    }

    /**
     * 拦截时那条 debug 串。
     *
     * <p>🔴 <b>把「哪一个条件不成立」摊开</b>：三种拦法（没带 token / 查不到 profile / 是游客）
     * 在用户侧回的是同一句话，⚠️ 排查时必须能分得出来是哪一种。</p>
     *
     * <p>📌 <b>它是个 public 方法而不是各处自己拼的字面量</b>，因为 {@code POST /upload}
     * 不走 {@link Router}、拿不到抛异常那条路（它得自己 {@code writeError}）。
     * 让它来这里取串，本类才是这条红线拒绝的<b>唯一产地</b> —— 否则那条结构检查就得开例外，
     * 而例外表一长，检查本身就废了。</p>
     *
     * @param where 可选的落点标注（如 {@code "upload"}），null 表示不标
     */
    public static String detail(EchoStore accounts, long accountId, String where) {
        String profileState = accounts == null || accountId <= 0 ? "n/a"
                : (accounts.profile(accountId) == null ? "missing" : "found");
        return "verifiable binding required (S1')"
                + (where == null ? "" : " [" + where + "]")
                + ": accountId=" + accountId
                + ", profile=" + profileState
                + ", guest=" + guestFlagOf(accounts, accountId);
    }

    /**
     * 只要求「有一个已鉴权账号」—— 🔴 <b>游客算过，这不是 {@code S1′}</b>。
     *
     * <p>{@code RequestContext.accountId()} 的 javadoc 逐字写着「公共路由如 {@code /auth/guest}
     * 时为 0」，所以 {@code id <= 0} 的准确含义是<b>压根没带 token</b>。这里正是它唯一站得住的地方。</p>
     *
     * <p>⚠️ <b>改这个方法的人请先确认自己要的不是 {@link #requireBound}。</b>
     * 两者的差别不是严格程度，是<b>问的问题不同</b>：一个问「你是谁」，一个问「你能不能被追责」。</p>
     */
    public static long requireAuthenticated(RequestContext ctx) {
        long id = ctx.accountId();
        if (id <= 0) {
            throw new ApiException(ApiException.UNAUTHORIZED, "先取一张通行证吧（游客也可以）。",
                    "authenticated account required: accountId=" + id);
        }
        return id;
    }

    private static String guestFlagOf(EchoStore accounts, long accountId) {
        if (accounts == null || accountId <= 0) {
            return "n/a";
        }
        AccountProfile profile = accounts.profile(accountId);
        return profile == null ? "n/a" : String.valueOf(profile.guest);
    }
}
