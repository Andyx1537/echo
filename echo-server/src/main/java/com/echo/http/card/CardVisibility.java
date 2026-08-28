package com.echo.http.card;

import java.util.List;

/**
 * 卡级可见性三档（公开 / 亲友 / 私密）与硬约束「🔴 <b>卡不得宽于窗</b>」。
 *
 * <h2>为什么是三档而不是两档</h2>
 *
 * <p>窗已经是三档。卡只做两档会出现<b>表达不出来的组合</b>——比如「窗公开、这张卡只给亲友」
 * 这种最常见的诉求就没有落点。{@code t_memory_card.visibility} 的 {@code CHECK}
 * 本来就是三值（{@code private|friends|public}），少做一档是应用层反过来收紧数据层。</p>
 *
 * <h2>🔴 硬约束「卡不得宽于窗」——选的是<b>读时取交集</b>，不是级联改卡</h2>
 *
 * <p>两个方向都要判，而两向用的是<b>两种机制</b>：</p>
 *
 * <table>
 *   <tr><th>方向</th><th>机制</th><th>落点</th></tr>
 *   <tr><td>作者想把<b>卡</b>设得比窗宽</td>
 *       <td><b>写时拒绝</b>，报错并告诉他先改窗</td>
 *       <td>{@link #assertNotWiderThanWindow}</td></tr>
 *   <tr><td><b>窗</b>收窄，卡变得比窗宽</td>
 *       <td><b>读时取交集</b>，卡行一个字都不改</td>
 *       <td>{@link #effective}</td></tr>
 * </table>
 *
 * <p>⚠️ 🔴 <b>窗收窄那一向是最容易漏的</b>：用户把窗从 {@code public} 改成 {@code friends}，
 * 卡若不跟着收，<b>公开的卡还留在广场上</b>——窗关了，内容还在外面。</p>
 *
 * <h3>为什么取交集而不是级联改卡</h3>
 *
 * <p>两种做法在<b>「用户又把窗改回来」</b>时行为不同，这正是取舍所在：</p>
 *
 * <ul>
 *   <li><b>级联改卡</b>：窗收窄时把 30 张公开卡逐行改成 {@code friends}。
 *       🔴 <b>作者的原始意图被就地覆盖，改回来时无法复原</b>——窗再改回 {@code public}，
 *       那 30 张卡<b>仍然是 {@code friends}</b>，因为没有任何地方记着它们原本是 {@code public}。
 *       要能复原就得再存一列「原始可见性」，那等于用两列去表达一件事，
 *       且这两列迟早会不一致。<br>
 *       ⚠️ 更实际的后果：作者只是<b>临时</b>把窗收一下（比如整理期间），
 *       回来就发现自己所有卡的公开状态被静默清空了，而他并没有做过这个决定。</li>
 *   <li>🟢 <b>读时取交集（本实现）</b>：卡行保留作者的原始意图，
 *       每次读取时算 {@code min(卡, 窗)}。窗收窄 ⇒ 公开卡立刻不再对外可见（约束达成）；
 *       窗改回来 ⇒ <b>原样恢复公开</b>，因为作者的意图从来没被覆盖过。</li>
 * </ul>
 *
 * <p>🔴 <b>代价要如实说清</b>：库里会长期存在「意图 = {@code public} 但实际对外不可见」的卡行。
 * ⚠️ <b>所以任何判「这张卡对外可见吗」的地方都不许直接读那一列</b>，
 * 必须过 {@link #effective}。直接读会得到一个偏宽的答案，
 * 而<b>偏宽的可见性判断就是内容泄漏</b>。这是取交集这条路唯一的、也是真实的风险点。</p>
 *
 * <h3>🔴 这条纪律不再只靠注释撑着（2026-08-27）</h3>
 *
 * <p>上一版这里就写着「不许直接读」，但<b>它只是一句注释</b> —— 而漏掉它的表现
 * 不是报错，是<b>多发了一条内容</b>。既然是泄漏方向的风险，靠人记不住。现在有两道：</p>
 *
 * <ol>
 *   <li><b>编译期</b>：字段已从 {@code visibility} 改名为
 *       {@link com.echo.http.model.ModerationModels.MemoryCard#visibilityIntent}。
 *       ⚠️ 名字自己说了它是<b>意图</b>而不是答案；
 *       🔴 <b>并且让检索第一次成为可能</b> —— 此前 {@code card.visibility} 与
 *       {@code pet.visibility}（窗级，直接读是<b>对的</b>）在 grep 里长得一模一样，
 *       所以「扫出所有卡级裸读」这件事<u>做不到</u>。</li>
 *   <li><b>测试期</b>：{@code probe/…/VisibilityScanProbe} 扫全仓，
 *       每一处访问都必须是「喂给本类的判定函数」「存储层搬运」「逐条登记过理由」三者之一。
 *       跑法见 {@code docs/BUILD-VERIFICATION.md §4}。</li>
 * </ol>
 *
 * <p>⚠️ <b>边界要说清，别高估它</b>：SQL 字符串里的 {@code visibility} 列扫不出对错，
 * 反射绕得过去，白名单也可以被人加一行绕过。🔴 <b>它提供的只有一件事 ——
 * 把「忘了过 effective()」这种看不见的遗漏，变成「往白名单加一行」这种必须写进 diff
 * 的动作。</b>拦不住存心的人，但能拦住手快的人，而后者才是这类缺陷的实际来源。</p>
 *
 * <p>📌 与之配套：写时拒绝（{@link #assertNotWiderThanWindow}）保证<b>正常路径下</b>
 * 卡不会宽于窗，所以取交集实际只在「窗收窄」这一种情况下真的起作用。
 * 两者不是二选一，是一前一后。</p>
 *
 * <h2>档位顺序与 fail-closed</h2>
 *
 * <p>{@code private < friends < public}。🔴 <b>无法识别的取值一律按最窄（{@code private}）算</b>——
 * 脏数据、新增档位、拼写错误的方向必须是「看不见」而不是「谁都能看」。</p>
 */
public final class CardVisibility {

    public static final String PRIVATE = "private";
    public static final String FRIENDS = "friends";
    public static final String PUBLIC = "public";

    /** 三档，由窄到宽。顺序即档位序，不要重排。 */
    public static final List<String> TIERS = List.of(PRIVATE, FRIENDS, PUBLIC);

    private CardVisibility() {
    }

    /** 取值是否是三档之一。 */
    public static boolean isValid(String v) {
        return TIERS.contains(v);
    }

    /**
     * 档位序：{@code private=0 < friends=1 < public=2}。
     *
     * <p>🔴 <b>无法识别的一律回 0（最窄）</b>，理由见类文档最后一节。</p>
     */
    public static int rank(String v) {
        if (FRIENDS.equals(v)) {
            return 1;
        }
        if (PUBLIC.equals(v)) {
            return 2;
        }
        return 0;
    }

    /**
     * 🔴 <b>生效可见性 = min(卡, 窗)</b>——判「这张卡现在对谁可见」的<b>唯一</b>入口。
     *
     * <p>⚠️ <b>不要绕过它去直接读 {@code card.visibility}</b>。窗收窄之后那一列是偏宽的
     * （卡行刻意不跟着改，见类文档），直接读会把不该看见的内容发出去。</p>
     *
     * @param cardVisibility   卡上存的作者意图
     * @param windowVisibility 所属窗当前的可见性
     * @return 两者中较窄的那一档
     */
    public static String effective(String cardVisibility, String windowVisibility) {
        return rank(cardVisibility) <= rank(windowVisibility) ? normalize(cardVisibility)
                : normalize(windowVisibility);
    }

    /** 生效可见性是否对外公开（广场只收这一档）。 */
    public static boolean effectivelyPublic(String cardVisibility, String windowVisibility) {
        return PUBLIC.equals(effective(cardVisibility, windowVisibility));
    }

    /** 卡是否比窗宽。 */
    public static boolean widerThanWindow(String cardVisibility, String windowVisibility) {
        return rank(cardVisibility) > rank(windowVisibility);
    }

    /**
     * 写时闸门：卡不得设得比窗宽。
     *
     * <p>🔴 <b>报错而不是静默收窄。</b>静默收窄的话，作者点了「公开」、界面回了成功，
     * 而这张卡实际上只有亲友能看——⚠️ <b>他会以为自己已经发出去了</b>，
     * 而这个误解要等到「怎么没人看」才会被发现，那时已经过了内容的分发窗口期。</p>
     *
     * @throws IllegalArgumentException 卡宽于窗时抛出，由端点转成对用户的话
     */
    public static void assertNotWiderThanWindow(String cardVisibility, String windowVisibility) {
        if (widerThanWindow(cardVisibility, windowVisibility)) {
            throw new IllegalArgumentException(
                    "card visibility " + cardVisibility + " is wider than window "
                            + windowVisibility);
        }
    }

    /** 归一化到三档之一；不认识的回 {@link #PRIVATE}。 */
    public static String normalize(String v) {
        return isValid(v) ? v : PRIVATE;
    }
}
