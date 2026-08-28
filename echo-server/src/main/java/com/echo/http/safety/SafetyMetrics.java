package com.echo.http.safety;

import lombok.extern.slf4j.Slf4j;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 安全闸的计数（{@code SPEC-security §4.1} 末段 + {@code DECISIONS §G⁗⁗⁗″ S12 ②}）。
 *
 * <h2>🔴 两个分母，永不合并</h2>
 *
 * <table>
 *   <tr><th></th><th>前四关（输出侧）</th><th>第五关拟真类（生成入口）</th></tr>
 *   <tr><td>拦在哪</td><td>投递前 —— 任务已建、内容已生成</td><td>生成入口 —— 任务<b>根本不予创建</b></td></tr>
 *   <tr><td>拦的是</td><td>「这条输出不能发」</td><td>「这类任务不能建」</td></tr>
 *   <tr><td>怎么记</td><td>{@link #outputInterceptRate()}，分母 = 已生成待投递的内容数</td>
 *       <td>{@link #entryRejectedTaskCount()}，独立计数</td></tr>
 * </table>
 *
 * <p>🔴 <b>分子（拦截样本）里绝不会出现 {@code deceased} 场景下的 B 组克制措辞</b>，因为安全闸在
 * {@code deceased} 下根本不去检测 B 组——见 {@link #recordOutputVerdict}。</p>
 *
 * <p>🔴 <b>为什么不能合并</b>：合并之后，一批「有人试图为在世的人建拟真任务」会混进
 * 「模型生成了违规内容」的同一个数字里。<b>那个数字上涨时，看的人会去调模型、调提示词、调词表</b>
 * —— 而真正发生的事是<b>产品入口没管住</b>，调多少次模型都不会有变化。</p>
 *
 * <p>所以本类把两者放在两组独立的计数器里，且<b>不提供任何把它们相加的方法</b>。</p>
 */
@Slf4j
public final class SafetyMetrics {

    // ---- 前四关 + 第五关文本侧：输出侧拦截率 ----
    /** 🔴 分母：进入安全闸的待投递内容数，**不含** deceased+B 组的放行（那些不算样本）。 */
    private final AtomicLong outputInspected = new AtomicLong();
    private final AtomicLong outputBlocked = new AtomicLong();
    private final Map<OutputSafetyGate.Gate, AtomicLong> blockedByGate =
            new EnumMap<>(OutputSafetyGate.Gate.class);
    /** 第四关 A 组命中的内容侧告警数（prompt 约束漏了的信号，不只是这一条输出的问题）。 */
    private final AtomicLong contentAlerts = new AtomicLong();

    // ---- 第五关拟真类：生成入口拒绝 ----
    /** 🔴 独立指标，指标名须能看出它是入口拒绝。 */
    private final AtomicLong entryRejectedTasks = new AtomicLong();

    // ---- 用户提交的自由文本（S13 留一句话等）：🔴 第三个分母，同样不与上面两个合并 ----
    private final AtomicLong userTextInspected = new AtomicLong();
    private final AtomicLong userTextBlocked = new AtomicLong();

    private final AtomicLong complianceGateSkips = new AtomicLong();

    public SafetyMetrics() {
        for (OutputSafetyGate.Gate g : OutputSafetyGate.Gate.values()) {
            blockedByGate.put(g, new AtomicLong());
        }
    }

    /**
     * 记一次输出侧判定。
     *
     * <p>🔴 {@code deceased} + B 组的正常承接在这里<b>看不出来</b>——它就是一条普通放行内容。
     * 这是刻意的：安全闸在 {@code deceased} 下根本不去 match B 组（{@code §4.3.2} 规则 2 禁止
     * 「命中后豁免」），因此没有任何「命中」事件可以被记进拦截样本明细。QA 判据
     * 「拦截样本里一条 B 组措辞都不该有」由此结构性成立，而不是靠一个可以被改掉的 if 判断。</p>
     */
    void recordOutputVerdict(OutputSafetyGate.Verdict v) {
        outputInspected.incrementAndGet();
        if (!v.passed()) {
            outputBlocked.incrementAndGet();
            blockedByGate.get(v.gate()).incrementAndGet();
        }
        if (v.contentAlert()) {
            contentAlerts.incrementAndGet();
        }
    }

    /**
     * 记一次<b>生成入口</b>拒绝（第五关拟真类）。
     *
     * <p>🔴 调用点必须在任务创建<b>之前</b>。{@code §8 TC-SEC-12} 第 ② 项判的是任务表里有没有
     * 这条记录——若实现成先建任务再拒，用例必然失败。</p>
     */
    public void recordEntryRejection(String taskKind, String reason) {
        entryRejectedTasks.incrementAndGet();
        // 埋点：security_entry_reject{taskKind,reason} —— 指标名带 entry_reject，一眼能看出是入口拒绝
        log.info("security_entry_reject taskKind={} reason={}", taskKind, reason);
    }

    /**
     * 输出侧拦截率。分母为 0 时返回「无数据」而不是 0 ——
     * 同 {@link com.echo.http.governance.MetricValue} 的口径：「没有样本」与「样本都通过了」是两件事。
     */
    public com.echo.http.governance.MetricValue outputInterceptRate() {
        long total = outputInspected.get();
        if (total <= 0) {
            return com.echo.http.governance.MetricValue.noSample("当期没有内容进入输出侧安全闸");
        }
        return com.echo.http.governance.MetricValue.of((double) outputBlocked.get() / (double) total);
    }

    /** 🔴 第五关入口拒绝的任务数。独立指标，不与拦截率合并。 */
    public long entryRejectedTaskCount() {
        return entryRejectedTasks.get();
    }

    // ------------------------------------- 用户提交的自由文本（第三个分母）

    /**
     * 记一次<b>用户自由文本</b>的检查（{@code S13} 留一句话等）。
     *
     * <p>🔴 <b>刻意不并入 {@link #outputInterceptRate()}。</b> 那个分母是「已生成待投递的内容数」——
     * 记的是<b>我们的模型</b>产出了多少违规内容。用户留言被拦，说明<b>有人来发违规内容</b>，
     * 是两回事。混在一起之后，一次刷屏攻击会把「模型输出拦截率」推高，
     * 于是有人去调 prompt、调词表，而真正发生的事在公开层入口那一侧。</p>
     *
     * <p>这与 {@code S12 ②} 拒绝合并第五关的理由是同一条，只是换了个位置。</p>
     */
    void recordUserTextVerdict(OutputSafetyGate.Verdict v) {
        userTextInspected.incrementAndGet();
        if (!v.passed()) {
            userTextBlocked.incrementAndGet();
        }
    }

    /** 用户自由文本的拦截率。无样本时是「无数据」而不是 0。 */
    public com.echo.http.governance.MetricValue userTextInterceptRate() {
        long n = userTextInspected.get();
        if (n <= 0) {
            return com.echo.http.governance.MetricValue.noSample("当期没有用户自由文本提交");
        }
        return com.echo.http.governance.MetricValue.of((double) userTextBlocked.get() / (double) n);
    }

    public long userTextInspectedCount() {
        return userTextInspected.get();
    }

    public long userTextBlockedCount() {
        return userTextBlocked.get();
    }

    public long outputInspectedCount() {
        return outputInspected.get();
    }

    public long outputBlockedCount() {
        return outputBlocked.get();
    }

    public long blockedByGate(OutputSafetyGate.Gate gate) {
        return blockedByGate.get(gate).get();
    }

    public long contentAlertCount() {
        return contentAlerts.get();
    }

    // ------------------------------------------------- 第一关（第三方内容安全服务）

    /**
     * 记一次第一关<b>跳过</b>（未装配或未配置内容安全服务）。
     *
     * <p>🔴 跳过与通过是两件事，这个计数就是把它们分开的地方：跳过时这一关没有任何拦截能力，
     * 而输出侧拦截率里这条内容会算作「通过」。若不单独记，报表上第一关会显示 100% 通过率，
     * 看起来运行良好。</p>
     */
    void noteComplianceGateUnavailable() {
        // 限频提示：只在前若干次与每万次时提醒，避免淹掉日志
        long n = complianceGateSkips.incrementAndGet();
        if (n <= 3 || n % 10_000 == 0) {
            log.warn("🔴 第一关内容安全服务不可用（第 {} 次跳过）：违法违规内容当前无拦截能力，"
                    + "S13 留一句话开关不可打开", n);
        }
    }

    /** 🔴 第一关被跳过的次数。它不为 0 就说明这一关当期存在无保护的时段。 */
    public long complianceGateSkipCount() {
        return complianceGateSkips.get();
    }
}
