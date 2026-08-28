package com.echo.http.governance;

/**
 * `S3` 五项治理能力（{@code DECISIONS §G⁗‴ S3} + {@code SPEC-security §4.5}）。
 *
 * <p>这五项是「公开层开放自由文本」的硬前置。{@code S13} 把它们从「上线前置」升格为
 * <b>开关的开启条件</b>：留一句话开关只允许在五项全部就绪后打开。</p>
 *
 * <p>🔴 <b>为什么就绪状态必须探测而不能配置</b>：如果就绪与否是一行可以 UPDATE 的数据，
 * 那么绕过前置条件的成本就从「改代码 + 走评审」退化成「改一行数据」——而 {@code S13} 要防的
 * 恰恰是这个：<em>打开它不需要改代码、不需要评审，流水上也看不出异常</em>。所以每一项都必须
 * 由代码在运行时去看**真实能力在不在**（路由挂没挂、store 注没注入），探测结果不落库、不可写。</p>
 */
public enum GovernanceCapability {

    /** 拉黑：单向、彻底不可见、不可感知（{@code S8}/{@code T7}）。 */
    BLOCK("拉黑", "单向拉黑后完全断流且对方无感知"),

    /** 举报：C 端提交入口必须常驻可达（{@code §4.5}）。 */
    REPORT("举报", "C 端可提交举报，且运营侧能看到"),

    /** 关闭互动：窗口主人可一键关掉全部互动入口。 */
    CLOSE_INTERACTION("关互动", "作者可关闭某一类或全部互动，约束在后端"),

    /** 审核队列：人工处置四动作 + 双流水。 */
    MODERATION_QUEUE("审核队列", "运营可对 UGC 做通过/驳回/下架/升级复核"),

    /** 文本安全闸：用户自由文本进公开层前的拦截能力。 */
    TEXT_SAFETY_GATE("文本安全闸", "用户提交的自由文本过词表与红线检测");

    private final String displayName;
    private final String requirement;

    GovernanceCapability(String displayName, String requirement) {
        this.displayName = displayName;
        this.requirement = requirement;
    }

    public String displayName() {
        return displayName;
    }

    /** 这一项「算就绪」的判据描述，用于把未就绪清单回给运营看。 */
    public String requirement() {
        return requirement;
    }
}
