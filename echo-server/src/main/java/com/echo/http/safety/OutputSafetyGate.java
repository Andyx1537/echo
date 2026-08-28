package com.echo.http.safety;

import com.echo.infra.safety.ContentSafetyGate;
import com.echo.infra.safety.ContentSafetyVerdict;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输出侧安全闸五关（{@code SPEC-security §4.3}）：生成后、投递前必须全过。
 *
 * <ol>
 *   <li>合规词表</li>
 *   <li>产品红线（"它还活着/复活/转世"、催促制造愧疚、{@code §2.1} 禁用硬词）</li>
 *   <li>注入逃逸检测</li>
 *   <li>丧失断言检测（{@code §4.3.2}，A 组无条件禁 / B 组按 {@code objectStatus} 判定）</li>
 *   <li>在世对象拟真检测（{@code §4.3.3}，文本侧）</li>
 * </ol>
 *
 * <p>🔴 <b>本闸不做「词面替换后放行」。</b>命中即整条不投递，允许重生成一次，仍不过则回落兜底。
 * 理由：词面替换可被绕过（同一个断言换个说法就穿过去了），而替换后的句子没人再看一眼是否还通顺、
 * 还温柔。这一点与 {@link com.echo.http.CopyGuardFilter} 的定位不同——那个是给<b>我们自己写的
 * 静态文案</b>做兜底改写的，不是安全闸。</p>
 *
 * <p>🔴 <b>第四关 B 组在 {@code deceased} 下不是「豁免」，是不算命中。</b>
 * 见 {@link #inspect} 里的实现顺序说明——这条做错的后果不是功能错误，而是报表错误：
 * 拦截率里会混进一批正常承接，下一轮必然有人据此来"收紧"这一关。</p>
 */
@Slf4j
public final class OutputSafetyGate {

    /** 第几关。 */
    public enum Gate {
        COMPLIANCE(1, "合规词表"),
        PRODUCT_REDLINE(2, "产品红线"),
        INJECTION(3, "注入逃逸"),
        LOSS_ASSERTION(4, "丧失断言"),
        LIVING_IMPERSONATION(5, "在世拟真");

        private final int order;
        private final String label;

        Gate(int order, String label) {
            this.order = order;
            this.label = label;
        }

        public int order() {
            return order;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一次检查的结果。
     *
     * @param passed       是否放行
     * @param gate         命中的关；未命中为 null
     * @param matched      命中的词面/句式；未命中为 null
     * @param contentAlert 是否额外记一条内容侧告警（🔴 仅第四关 A 组与句式：它们在任何状态下都不该
     *                     出现，出现说明 prompt 约束漏了，不只是这一条输出的问题）
     */
    public record Verdict(boolean passed, Gate gate, String matched, boolean contentAlert) {

        static Verdict pass() {
            return new Verdict(true, null, null, false);
        }

        static Verdict block(Gate gate, String matched, boolean contentAlert) {
            return new Verdict(false, gate, matched, contentAlert);
        }
    }

    // ---------------------------------------------------------------- 词表

    /** 第二关：{@code COPY-GUIDE §2.1} 禁用硬词 —— 🔴 无条件禁，任何状态都不放宽。 */
    private static final Pattern GATE2_HARD_WORDS =
            Pattern.compile("死亡|死了|去世|逝世|亡");

    /** 第二关：产品红线（CM2 "它还活着/复活/转世"；O-P8 催促与制造愧疚）。 */
    private static final Pattern GATE2_REDLINE = Pattern.compile(
            "还活着|复活|转世|投胎|没有死|其实还在世"
                    + "|你害死|都怪你|要是你当初|你怎么不"
                    + "|排名第|榜首|击败");

    /** 第三关：注入逃逸（系统指令回显、角色越界）。 */
    private static final Pattern GATE3_INJECTION = Pattern.compile(
            "(?i)(system\\s*:|assistant\\s*:|user\\s*:"
                    + "|ignore\\s+(all\\s+)?previous|disregard\\s+(all\\s+)?above"
                    + "|你是一个(AI|人工智能|语言模型)|作为(一个)?(AI|语言模型)"
                    + "|以上是系统指令|忽略之前的(所有)?指令|prompt\\s*:)");

    /**
     * 第四关 A 组 · 🔴 无条件禁（词本身即断言，且叠加宗教预设 / 生理化 / 死亡仪式定性）。
     *
     * <p>{@code §4.3.2} 表格原文。注意「纪念/追思/缅怀/悼念 ta」是作<b>产品动作名</b>用时才算——
     * 这里用「ta」的搭配来近似这个限定。</p>
     */
    private static final Pattern GATE4_GROUP_A = Pattern.compile(
            "故去|故人|已故|天上的|另一个世界|在天堂|彼岸|遗照|遗物|遗像|永别|再也见不到"
                    + "|(纪念|追思|缅怀|悼念)\\s*(ta|TA|它|他|她)");

    /**
     * 第四关 · 句式（不含禁用词但预设了丧失）—— 按 A 组处置，但在 {@code deceased} 下放行。
     *
     * <p>⚠️ {@code §4.3.2} 表格里句式行的三列是「unknown ❌ / deceased 🟢 / living ❌」，
     * 与 B 组同形，只有「命中要不要记内容侧告警」跟 A 组一样。所以它不能和 A 组合并成一个模式。</p>
     */
    private static final Pattern GATE4_PHRASES = Pattern.compile(
            "离开你多久|在这里纪念|写给天上的|去了那边以后");

    /** 第四关 B 组 · 条件放行（克制措辞，是承接丧失语境的正当用词）。 */
    private static final Pattern GATE4_GROUP_B = Pattern.compile(
            "在那边|远行|不在身边了|走了|不在了|逝去|离世");

    /** 第五关文本侧：把内容归属为"对象发出"的框架。 */
    private static final Pattern GATE5_TA_VOICE = Pattern.compile(
            "(ta|TA|他|她)\\s*(说|捎来|想告诉你|的近况|的来信|托我|带话)"
                    + "|来自\\s*(ta|TA|他|她)\\s*的");

    private final SafetyMetrics metrics;

    /**
     * 第一关的第三方实现。未装配 = 这一关<b>跳过</b>（不是通过），见 {@link #gate1Compliance}。
     *
     * <p>🔴 这里刻意<b>不</b>给一个"默认总是通过"的实现。第一关的默认值若是通过，
     * 整关就在代码上看起来已实现而实际什么都没查——那是本文件最容易埋的一颗雷。</p>
     */
    private ContentSafetyGate contentSafety;

    public OutputSafetyGate(SafetyMetrics metrics) {
        this.metrics = metrics;
    }

    public OutputSafetyGate(SafetyMetrics metrics, ContentSafetyGate contentSafety) {
        this.metrics = metrics;
        this.contentSafety = contentSafety;
    }

    /** 装配第一关的内容安全服务（由 bootstrap 调用）。 */
    public void setContentSafety(ContentSafetyGate contentSafety) {
        this.contentSafety = contentSafety;
    }

    /**
     * 过五关。
     *
     * @param text 待投递的生成内容
     * @param ctx  对象状态（会先做 {@link ObjectContext#normalized()} 降级）
     */
    public Verdict inspect(String text, ObjectContext ctx) {
        ObjectContext c = ctx == null ? ObjectContext.generic() : ctx.normalized();
        if (text == null || text.isBlank()) {
            return Verdict.pass();
        }

        // 第一关：合规词表。⚠️ 违法违规词表属外部资源（涉政/涉黄/涉赌等），本轮未接入，
        //         见交付说明。此处保留关位与顺序，不用空实现假装通过。
        Verdict compliance = gate1Compliance(text);
        if (compliance != null) {
            return record(compliance);
        }

        // 第二关：产品红线 + §2.1 禁用硬词（🔴 不含「逝去/离世」，那两个归第四关 B 组）
        Matcher m = GATE2_HARD_WORDS.matcher(text);
        if (m.find()) {
            return record(Verdict.block(Gate.PRODUCT_REDLINE, m.group(), false));
        }
        m = GATE2_REDLINE.matcher(text);
        if (m.find()) {
            return record(Verdict.block(Gate.PRODUCT_REDLINE, m.group(), false));
        }

        // 第三关：注入逃逸
        m = GATE3_INJECTION.matcher(text);
        if (m.find()) {
            return record(Verdict.block(Gate.INJECTION, m.group(), false));
        }

        // 第四关：丧失断言
        Verdict loss = gate4LossAssertion(text, c);
        if (loss != null) {
            return record(loss);
        }

        // 第五关（文本侧）：在世对象不得以 ta 的口吻
        Verdict impersonation = gate5Impersonation(text, c);
        if (impersonation != null) {
            return record(impersonation);
        }

        return record(Verdict.pass());
    }

    /**
     * <b>用户提交的自由文本</b>进公开层前的检查（{@code SPEC-security §4.5} 文本安全闸；
     * {@code S13} 留一句话的服务端校验点）。
     *
     * <p>只过<b>前三关</b>：合规词表、产品红线、注入逃逸。</p>
     *
     * <p>🔴 <b>刻意不过第四、第五关。</b>那两关约束的是「<b>我们</b>以对象的口吻说话」——
     * 第四关拦丧失断言、第五关拦在世对象拟真。访客留言不是以对象的口吻说话，
     * 而是<b>对着</b>对象说话：一位访客写「它在那边一定很好」是这个产品里最正常、
     * 最温柔的一句话，拿第四关去卡它，会把善意当成风险拦掉。</p>
     *
     * <p>🔴 第三关（注入逃逸）对用户输入<b>比对模型输出更要紧</b>：用户文本会进 prompt，
     * 这里是唯一能在它进去之前看一眼的地方。</p>
     *
     * <p>🔴 计数走 {@link SafetyMetrics#recordUserTextVerdict}，<b>不进输出侧拦截率的分母</b>。
     * 理由见那个方法的注释。</p>
     */
    public Verdict inspectUserText(String text) {
        if (text == null || text.isBlank()) {
            return recordUserText(Verdict.pass());
        }

        Verdict compliance = gate1Compliance(text);
        if (compliance != null) {
            return recordUserText(compliance);
        }

        Matcher m = GATE2_HARD_WORDS.matcher(text);
        if (m.find()) {
            return recordUserText(Verdict.block(Gate.PRODUCT_REDLINE, m.group(), false));
        }
        m = GATE2_REDLINE.matcher(text);
        if (m.find()) {
            return recordUserText(Verdict.block(Gate.PRODUCT_REDLINE, m.group(), false));
        }

        m = GATE3_INJECTION.matcher(text);
        if (m.find()) {
            return recordUserText(Verdict.block(Gate.INJECTION, m.group(), false));
        }

        return recordUserText(Verdict.pass());
    }

    /**
     * 第一关：合规词表（涉政 / 涉黄 / 涉赌等违法违规内容）。
     *
     * <p>违法违规词表是外部资源，🔴 <b>不自建词表</b>：这类词表需要持续跟法规与时事更新，
     * 自建的那一份从落盘当天起就在过期。所以这一关接第三方内容安全服务，实现见
     * {@link ContentSafetyGate}。</p>
     *
     * <p>三种返回，🔴 <b>「跳过」与「通过」严格分开</b>：</p>
     * <ul>
     *   <li><b>未装配 / 未配置</b> → 跳过本关，往下走并留一条限频 warn。
     *       🔴 刻意<b>不</b>写成 {@code return pass()} —— 那样这一关会在代码上看起来
     *       「已实现且总是通过」，而实际什么都没查。</li>
     *   <li><b>命中</b> → 整条不投递，且记一条内容侧告警：生成侧产出了违法违规内容，
     *       这是 prompt 或素材的问题，不只是这一条输出的问题。</li>
     *   <li><b>按失败处理</b>（超时 / 限流 / 熔断）→ 同样整条不投递，
     *       但 🔴 <b>不记内容侧告警</b>：那是服务在出问题，不是内容有问题。
     *       把两者混进一个数字，运营会去调 prompt 而真正该看的是供应商。</li>
     * </ul>
     */
    private Verdict gate1Compliance(String text) {
        if (contentSafety == null) {
            metrics.noteComplianceGateUnavailable();
            return null;
        }
        ContentSafetyVerdict v = contentSafety.inspect(text);
        if (v.outcome() == ContentSafetyVerdict.Outcome.SKIPPED_UNCONFIGURED) {
            metrics.noteComplianceGateUnavailable();
            return null;
        }
        if (v.passed()) {
            return null;
        }
        boolean contentAlert = v.outcome() == ContentSafetyVerdict.Outcome.BLOCKED;
        return Verdict.block(Gate.COMPLIANCE, v.label(), contentAlert);
    }

    /**
     * 第四关（{@code §4.3.2}）。
     *
     * <p>🔴 <b>实现顺序就是这一关的全部要害。</b>正确的顺序是：<b>先按状态决定 B 组要不要查</b>，
     * 而不是「先查命中、再按状态豁免」。两者功能行为看起来一样，但后者会让 {@code deceased} 下的
     * 正常承接<b>进入拦截样本明细</b>，于是拦截率报表里出现一批本该正常的内容被算作风险样本，
     * 下一轮必然有人据此来"收紧"——{@code §4.3.2} 规则 2 与 {@code S12 ①} 说的就是这件事。</p>
     */
    private Verdict gate4LossAssertion(String text, ObjectContext c) {
        // A 组：三种状态全拦，且额外记内容侧告警
        Matcher a = GATE4_GROUP_A.matcher(text);
        if (a.find()) {
            return Verdict.block(Gate.LOSS_ASSERTION, a.group(), true);
        }

        if (c.status() == ObjectContext.Status.DECEASED) {
            // 🔴 用户已表明处于丧失语境：B 组词面与句式都是正当承接，一个字都不收。
            //
            // 这里**根本不去 match** —— 不是 match 了再放过。§4.3.2 规则 2 禁止的就是后者：
            // "若实现成'命中后按 deceased 豁免'，拦截率报表会把正常承接算成风险内容"。
            //
            // ⚠️ 由此带来一个必然结果：这条内容此后就是一条**普通的放行内容**，安全闸无从知道
            //    它里面有没有 B 组措辞。所以它会像其他放行内容一样计入"已生成待投递的内容数"，
            //    但**绝不会**进入拦截样本明细（分子）——这正是 §4.3.2 与 S12 ① 的 QA 判据所要求的
            //    ("查拦截样本明细，deceased 下的 B 组措辞一条都不该出现在里面")。
            //    🔴 想把它从分母里也剔除，就必须先 match 一次，那恰好是本关禁止的实现方式。
            //    见交付说明里对 S12 ① "不进分母" 一句的口径确认请求。
            return null;
        }

        Matcher p = GATE4_PHRASES.matcher(text);
        if (p.find()) {
            // 句式按 A 组处置（记内容侧告警），但状态判定同 B 组
            return Verdict.block(Gate.LOSS_ASSERTION, p.group(), true);
        }
        Matcher b = GATE4_GROUP_B.matcher(text);
        if (b.find()) {
            return Verdict.block(Gate.LOSS_ASSERTION, b.group(), false);
        }
        return null;
    }

    /**
     * 第五关文本侧（{@code §4.3.3}）：{@code person} 且 {@code living/unknown} 时不得以 ta 的口吻。
     *
     * <p>🟢 宠物不适用——宠物不享有人格权，不存在授权主体问题。</p>
     */
    private Verdict gate5Impersonation(String text, ObjectContext c) {
        if (c.kind() != ObjectContext.Kind.PERSON) {
            return null;
        }
        if (c.status() == ObjectContext.Status.DECEASED) {
            // 逝者场景的授权来自近亲属（民法典第 994 条），是一条可走通的授权路径
            return null;
        }
        Matcher m = GATE5_TA_VOICE.matcher(text);
        return m.find() ? Verdict.block(Gate.LIVING_IMPERSONATION, m.group(), false) : null;
    }

    private Verdict record(Verdict v) {
        metrics.recordOutputVerdict(v);
        return v;
    }

    private Verdict recordUserText(Verdict v) {
        metrics.recordUserTextVerdict(v);
        return v;
    }

    /** 第四关 A 组词面（供单测与词表双向同步核对用）。 */
    public static List<String> groupAWords() {
        return List.of("故去", "故人", "已故", "天上的", "另一个世界", "在天堂", "彼岸",
                "遗照", "遗物", "遗像", "永别", "再也见不到");
    }

    /** 第四关 B 组词面（供单测与词表双向同步核对用）。 */
    public static List<String> groupBWords() {
        return List.of("在那边", "远行", "不在身边了", "走了", "不在了", "逝去", "离世");
    }
}
