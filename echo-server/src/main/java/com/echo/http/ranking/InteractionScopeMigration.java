package com.echo.http.ranking;

/**
 * 互动数据挂在<b>窗</b>上还是挂在<b>卡</b>上——以及 🔴 <b>为什么它永远不会变成「卡」</b>。
 *
 * <h2>🔴 2026-08-27 更正：这里原先描述的迁移已被裁定取消</h2>
 *
 * <p>本类原先写的是「共鸣厅的流量正在从宠物窗口迁到回忆卡，分三步走，第③步是<b>献花与记得
 * 迁到卡级</b>」，并把 {@link #CURRENT} 说成「第③步落地时改成 {@link #CARD}」。</p>
 *
 * <p><b>第③步不会落地。</b>{@code DECISIONS RK-H}（2026-08-26 制作人裁定）定的是
 * <b>卡级只增加一列入口归因，互动本身不搬家</b>：</p>
 *
 * <ul>
 *   <li>窗级<b>仍然</b>一人一次，决定暖意与面孔墙（{@code D8} 原意一字不改）；</li>
 *   <li>卡级另记一笔「从哪张卡记得的」，🔴 <b>仅供归因、不参与任何计数</b>，
 *       且不进任何响应体、不展示、不排行、不比较；</li>
 *   <li>🔴 <b>暖意 / 面孔墙 / {@code warmthLevel} / 独立互动者数一律仍从窗级取。</b></li>
 * </ul>
 *
 * <p>所以 {@link #CURRENT} 是<b>终态</b>，不是过渡态；{@link #isCardLevel()} <b>恒为
 * {@code false}</b>，而且没有任何一天它会变成 {@code true}。前一版把它写成
 * 「改一行常量即可解除」，🔴 <b>那一行永远不会有人来改，因为要改的那件事已经被否掉了。</b></p>
 *
 * <h2>🔴 这个更正的实际后果：别再拿它当 S4 的开关</h2>
 *
 * <p>{@link S4DrainPolicy} 原先把「S4 能不能生效」挂在 {@link #isCardLevel()} 上。既然这个
 * 判据恒假且永远恒假，那就等于<b>把 S4 自然流掉永久关死，而代码读起来像是「暂时关着，
 * 等迁移完成就自己开」</b>。⚠️ 两者的差别不在行为上（今天都是关着），在于
 * <b>下一个人读完之后会做什么</b>：他会去等一个不会到来的迁移，而不是去补那条真正缺的裁定。</p>
 *
 * <p>🔴 <b>S4 关闭的真实理由已经移到 {@link S4DrainPolicy#RULED_OPEN}，写成一个显式开关。</b>
 * 本类退回它本来的职责：<b>只回答「卡级独立互动者数可不可信」这一个问题</b>，
 * 不再兼任任何机制的准入闸。</p>
 *
 * <h2>为什么这个类还留着</h2>
 *
 * <p>因为它回答的那个问题<b>本身没有失效，反而变成了永久事实</b>：{@code t_flower_log} 与
 * {@code t_remember} 记的是 <b>petId</b>（见 {@link com.echo.http.model.Models.FlowerLog#windowId}
 * 与 {@link com.echo.http.model.Models.ReactionMark#windowId}），于是<b>任何一张卡的卡级独立
 * 互动者数恒为 0</b>——不是「这张卡没人理」，是「互动根本没记在卡上」。
 * 🔴 <b>这两件事在数据里长得一模一样</b>，而按 {@code RK-H} 它们将<b>一直</b>长得一模一样。</p>
 *
 * <p>任何拿到一个「卡级互动者数」的调用点都该先过一次
 * {@link #cardInteractorCountIsTrustworthy()}。删掉这个类，那些 0 就会被当成事实读。</p>
 */
public final class InteractionScopeMigration {

    /** 互动记在宠物窗口上（{@code petId}）。🔴 按 {@code RK-H} 这是<b>终态</b>，不是过渡态。 */
    public static final String WINDOW = "window";

    /**
     * 互动记在回忆卡上（{@code t_memory_card.id}）。
     *
     * <p>🔴 <b>这个取值已被 {@code RK-H} 排除，不是「还没到」的目标态。</b>保留这个常量只为了
     * 让 {@link #isCardLevel()} 的比较有个具名的右操作数，以及让本类的文档能指着它说
     * 「不会走到这里」。⚠️ <b>不要把它当 TODO 读。</b></p>
     */
    public static final String CARD = "card";

    /**
     * 当前口径，🔴 <b>同时也是终态</b>：{@link #WINDOW}。
     *
     * <p>⚠️ <b>不要改这一行。</b>前一版在这里写「第③步完成时改成 {@code CARD}」——那一步已被
     * {@code RK-H} 否掉（互动本身不搬家）。真要让互动搬家，需要的是<b>显式改写 {@code D8}</b>
     * （窗级一人一次是它定的）并重新定义暖光语义，🔴 <b>那是另一次裁定，不是改一个常量。</b></p>
     */
    public static final String CURRENT = WINDOW;

    private InteractionScopeMigration() {
    }

    /**
     * 互动是否记在卡级。🔴 <b>恒为 {@code false}，且按 {@code RK-H} 永远如此。</b>
     *
     * <p>⚠️ <b>不要把这个方法当作任何机制的准入闸。</b>它恒假，所以任何形如
     * {@code isCardLevel() && 别的条件} 的表达式都恒假——那个「别的条件」于是<b>从来没被求值过，
     * 断言它的用例也就什么都没验</b>。这正是 {@link S4DrainPolicy} 之前踩的坑。</p>
     */
    public static boolean isCardLevel() {
        return CARD.equals(CURRENT);
    }

    /**
     * 卡级独立互动者数<b>是不是可信的</b>。🔴 恒为 {@code false}。
     *
     * <p>与 {@link #isCardLevel()} 同义，单独留一个名字是给读代码的人看的：
     * 调用点关心的从来不是「迁移做没做」，而是<b>「我手上这个 0 能不能当真」</b>。
     * 答案是不能，而且按 {@code RK-H} 永远不能——卡级那一列只记归因、不参与计数。</p>
     */
    public static boolean cardInteractorCountIsTrustworthy() {
        return isCardLevel();
    }
}
