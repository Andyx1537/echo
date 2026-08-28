package com.echo.http.card;

import com.echo.http.model.ModerationModels.CardStatus;
import com.echo.http.model.ModerationModels.MemoryCard;

import java.util.Comparator;

/**
 * 置顶（{@code pinnedAt}）的口径。
 *
 * <h2>🔴 作用域：只在作者自己那一页的公开卡列表</h2>
 *
 * <p>🔴 <b>绝不进共鸣厅公开流。</b>共鸣厅的顺序由排序引擎（权重衰减 / 制动 / SURGE）决定，
 * 而置顶是一个由作者单方面设定、不随时间衰减的强制靠前——⚠️ <b>让它进公开流等于开一个
 * 绕过排序机制的后门</b>，而且是<b>正面打架</b>：衰减模型刚把一张老卡的权重压下去，
 * 置顶又把它顶回第一位，两个机制会在同一块屏上互相抵消。</p>
 *
 * <p>所以 {@link #ORDER} 这个比较器<b>只允许用在作者主页的卡列表上</b>。
 * {@code GET /plaza} 不许用它，那里的顺序归排序引擎。</p>
 *
 * <h2>能置顶的条件</h2>
 *
 * <ul>
 *   <li>{@code visibility = public}（生效档位，见 {@link CardVisibility#effective}）；</li>
 *   <li><b>已过审</b>：{@code status = public} 且 {@code reviewedAt != null}；</li>
 *   <li>未软删。</li>
 * </ul>
 *
 * <p>🔴 <b>为什么两个条件都要判 {@code status} 和 {@code reviewedAt}</b>：
 * {@code reviewedAt} 只写一次、写后不可变（库里有触发器守着），所以一张<b>被下架的卡</b>
 * 它的 {@code reviewedAt} 仍然非空。只判 {@code reviewedAt} 会让下架的卡还能被置顶。</p>
 *
 * <h2>🔴 自动解除，不留悬空</h2>
 *
 * <p>取消发布 / 审核打回 / 运营下架时，{@code pinnedAt} 必须<b>一并清空</b>——
 * 见 {@link #shouldUnpin}。⚠️ <b>留着悬空的 {@code pinnedAt} 有两个后果</b>：
 * ① 卡重新过审时会<b>自己跳回置顶位</b>，而作者并没有再做这个决定；
 * ② 上限 3 张的计数会把这些隐形置顶算进去，作者会遇到「我只置顶了 1 张，
 * 系统说满了」——而他<b>看不到</b>那两张是谁，因为它们不在公开列表里。</p>
 */
public final class PinPolicy {

    /** 置顶上限的默认值。🔴 后台可配，配置缺失时用这个。 */
    public static final int DEFAULT_MAX_PINNED = 3;

    /** 后台配置项键名。 */
    public static final String SETTING_MAX_PINNED = "card.pinned.max";

    /**
     * 作者主页公开卡列表的顺序：🔴 {@code pinnedAt DESC NULLS LAST, publishedAt DESC}。
     *
     * <p>{@code NULLS LAST} 是关键：没置顶的卡（{@code pinnedAt = null}）排在<b>所有</b>
     * 置顶卡之后。⚠️ Java 里 {@code Comparator.nullsLast} 配 {@code reverseOrder} 很容易写反，
     * 所以这里显式写出两段比较，并在
     * {@code PinPolicyProbe} 里断言过「置顶的一定在未置顶的前面」。</p>
     *
     * <p>📌 与 SQL 侧 {@code ORDER BY "pinnedAt" DESC NULLS LAST, "publishedAt" DESC} 等价。
     * 🔴 两处必须一致，否则内存态联调与落库跑出来的顺序不同，
     * 而那种不一致只在有人置顶之后才显形。</p>
     */
    public static final Comparator<MemoryCard> ORDER = (a, b) -> {
        int byPinned = comparePinned(a.pinnedAt, b.pinnedAt);
        if (byPinned != 0) {
            return byPinned;
        }
        // publishedAt DESC；两边都为 null 时按 id 倒序兜底，保证顺序稳定（分页不重不漏）
        long pa = a.publishedAt == null ? Long.MIN_VALUE : a.publishedAt;
        long pb = b.publishedAt == null ? Long.MIN_VALUE : b.publishedAt;
        int byPublished = Long.compare(pb, pa);
        return byPublished != 0 ? byPublished : Long.compare(b.id, a.id);
    };

    /** {@code pinnedAt DESC NULLS LAST}：非空在前、非空之间按时间倒序。 */
    private static int comparePinned(Long a, Long b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;   // a 无置顶 → 排后面
        }
        if (b == null) {
            return -1;  // b 无置顶 → a 排前面
        }
        return Long.compare(b, a);   // 都置顶了：晚置顶的在前
    }

    private final int maxPinned;

    /** 用默认上限（3）。 */
    public PinPolicy() {
        this(DEFAULT_MAX_PINNED);
    }

    /**
     * @param maxPinned 上限，后台可配。{@code <= 0} 视为配置缺失，回落到
     *                  {@link #DEFAULT_MAX_PINNED} —— 🔴 <b>不许当成「不限」</b>，
     *                  那会让一次配置失误变成「整页都是置顶」
     */
    public PinPolicy(int maxPinned) {
        this.maxPinned = maxPinned > 0 ? maxPinned : DEFAULT_MAX_PINNED;
    }

    /** 当前生效的置顶上限。 */
    public int maxPinned() {
        return maxPinned;
    }

    /**
     * 这张卡现在能不能被置顶。
     *
     * @param windowVisibility 所属窗的可见性——🔴 <b>必须传</b>，
     *                         因为「公开」判的是<b>生效</b>档位而不是卡上那一列
     *                         （窗收窄时卡列是偏宽的，见 {@link CardVisibility}）
     */
    public boolean pinnable(MemoryCard card, String windowVisibility) {
        return card != null
                && card.deletedAt == null
                && CardStatus.PUBLIC.equals(card.status)
                && card.reviewedAt != null
                && CardVisibility.effectivelyPublic(card.visibilityIntent, windowVisibility);
    }

    /**
     * 🔴 是否该<b>自动解除</b>置顶。判据与 {@link #pinnable} 互为反面，但语义不同：
     * {@code pinnable} 问「能不能置」，本方法问「已经置了的要不要摘掉」。
     *
     * <p>分成两个方法而不是一个取反，是因为它们的<b>调用时机</b>不同：前者在作者点置顶时，
     * 后者在<b>状态变更时</b>（审核打回、下架、作者取消发布、作者收窄可见性）。
     * ⚠️ <b>后者最容易漏</b>——它没有一个「用户点了什么」的触发点，
     * 而是挂在别人的动作后面。</p>
     */
    public boolean shouldUnpin(MemoryCard card, String windowVisibility) {
        return card != null && card.pinnedAt != null && !pinnable(card, windowVisibility);
    }

    /** 已置顶数是否已达上限。 */
    public boolean atCapacity(int currentlyPinned) {
        return currentlyPinned >= maxPinned;
    }
}
