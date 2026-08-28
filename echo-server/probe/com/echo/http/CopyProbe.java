package com.echo.http;

import com.echo.http.safety.OutputSafetyGate;
import com.echo.http.safety.SafetyMetrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 兜底文案池的合规断言 —— 🔴 <b>{@code EchoFallbackCopyTest} 的可执行版本</b>。
 *
 * <h2>为什么要把它从 {@code src/test} 抄过来</h2>
 *
 * <p>🔴 <b>{@code EchoFallbackCopyTest} 写得很好，但它一次都没有跑过。</b>
 * 本工程 {@code mvn test} 禁用（`surefire` 的 fork 在沙箱里会被打崩，见
 * {@code docs/BUILD-VERIFICATION.md §1.1}），所以那个文件里的每一条断言
 * <b>都是不执行的</b>。</p>
 *
 * <p>⚠️ 这比「没有测试」更坏：它<b>看起来</b>有一条机器检查在守着兜底池，
 * 于是下一个往池子里加句子的人会以为踩线会被拦下来。
 * 🔴 <b>一个不运行的断言和一个恒真的断言，效果完全一样</b> ——
 * 而这恰好是 {@code BUILD-VERIFICATION} 全篇在讲的那类缺陷，
 * 只不过这一次的成因不是短路求值，是<b>整个执行器没启动</b>。</p>
 *
 * <p>本探针比原测试<b>严一档</b>：原测试的全仓扫描只查「等你 / 等着你」两个词，
 * 而池内断言查 13 个。这里把<b>全仓扫描也提到 13 个</b> —— 词表既然已经写出来了，
 * 只用其中两个去扫源码没有道理。</p>
 */
public final class CopyProbe {

    static int failures = 0;

    /** 与 {@code EchoFallbackCopyTest.ABSENCE_OR_INVITE_CUES} 同源。 */
    static final List<String> CUES = List.of(
            "等你", "等着你", "在等", "等了很久", "你有空", "来看看它", "来陪它",
            "你多久没", "你好久没", "它很失落", "它很难过", "以为你", "不来了");

    /**
     * 🔴 全仓扫描的例外，<b>每条都必须写清为什么</b>。
     *
     * <h2>为什么必须有这张表，以及为什么它只能这么短</h2>
     *
     * <p>{@code EchoFallbackCopyTest} 的全仓扫描只查「等你 / 等着你」两个词，
     * 理由写得很清楚：<b>维护一条「一个都不许有」的规则，比维护一张例外清单便宜。</b>
     * 那个判断对<b>那两个词</b>是对的 —— 它们无论出现在哪都是缺席归因。</p>
     *
     * <p>⚠️ 但把 13 个词全用来扫源码就不成立了：其中两个词
     * 🔴 <b>只有在「它」作主语时才是违规</b>，换个主语是完全正常的话。
     * 不给例外的话，唯一的出路是去改写两处本来没问题的文案 ——
     * <b>那是让规则去迁就工具，不是让工具服务规则。</b></p>
     *
     * <p>🔴 <b>加一条例外的门槛：必须能说出「这里的『它』指的不是宠物」或
     * 「这句话的主语不是宠物」。</b>说不出来就是违规，去改文案。</p>
     */
    static final List<String> SCAN_EXEMPT = List.of(
            // 「它」= 出问题的那个页面 / 那次操作，不是宠物。这是通用错误重试提示，
            // 出现在 HttpGateway / ModerationApi / PgModerationStore / EchoApi 共 4 处。
            "这里出了点小状况，待会儿再来看看它好吗？",
            // 🔴 主语是「我」而不是「它」：这是 §11 我的光谱的「内在阴影」兜底，
            // 说的是<b>用户自己</b>的内省状态（solo 自我回溯，没有宠物在场）。
            // CR2 管的是「不得把<b>它</b>的状态归因于你不在场」，这句里没有它、也没有缺席归因。
            "有时候我会安静下来，像在等一个还没说出口的词。");

    public static void main(String[] args) throws Exception {
        System.out.println("\n---- 兜底池逐条过输出闸（用生产路径同一个 ObjectContext） ----");
        OutputSafetyGate gate = new OutputSafetyGate(new SafetyMetrics());
        List<String> pool = allFallbackLines();
        check("兜底池非空（gateOrFallback 会对它取随机下标）", !pool.isEmpty());
        for (String line : pool) {
            OutputSafetyGate.Verdict v = gate.inspect(line, EchoApi.fallbackObjectContext());
            check("过闸：「" + line + "」"
                            + (v.passed() ? "" : " ← 命中第 " + (v.gate() == null ? "-" : v.gate().order())
                            + " 关（" + v.matched() + "）"),
                    v.passed());
        }

        System.out.println("\n---- 🔴 CR2 + DP2：不得归因于用户不在场、不得拉回访 ----");
        // 安全闸查不出这一类：「等你有空来陪它坐一会儿」温柔、通顺、不含禁用词，
        // 它踩的是产品调性红线而不是安全红线，所以必须单列。
        for (String line : pool) {
            for (String cue : CUES) {
                check("「" + line + "」不含「" + cue + "」", !line.contains(cue));
            }
        }

        System.out.println("\n---- 🔴 全仓扫描 src/main/java 的字符串字面量（13 个词，比原测试严） ----");
        Path root = Path.of("src", "main", "java");
        // 🔴 扫不到目录就等于这条断言永远通过 —— 那正是假绿，宁可让它红
        check("源码目录存在（扫不到就是假绿）cwd=" + Path.of("").toAbsolutePath(),
                Files.isDirectory(root));

        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    String trimmed = lines.get(i).stripLeading();
                    // 注释行跳过：解释「为什么不能这么写」时必须能引用这些词
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")
                            || trimmed.startsWith("/*")) {
                        continue;
                    }
                    for (String lit : scannableLiteralsOf(lines.get(i))) {
                        if (SCAN_EXEMPT.contains(lit)) {
                            continue;
                        }
                        for (String cue : CUES) {
                            if (lit.contains(cue)) {
                                offenders.add(f + ":" + (i + 1) + "  「" + lit.strip()
                                        + "」含「" + cue + "」");
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new AssertionError("扫描 src/main/java 失败", e);
        }

        // 🔴 断「扫过东西」而不只断「没命中」：扫了 0 个文件时 offenders 也是空的，
        //    两种情况在 isEmpty() 上不可区分（同 BUILD-VERIFICATION §5 拒收当成功）
        check("确实扫到了源文件（扫了 " + scanned + " 个）", scanned > 100);
        if (!offenders.isEmpty()) {
            offenders.forEach(o -> System.out.println("       " + o));
        }
        check("🔴 下发给用户的文案不含任何缺席/回访线索（命中 " + offenders.size() + " 处）",
                offenders.isEmpty());

        System.out.println(failures == 0 ? "\n=== 全部通过 ===" : "\n=== 🔴 " + failures + " 条失败 ===");
        if (failures > 0) {
            System.exit(1);
        }
    }

    static List<String> allFallbackLines() {
        return Stream.concat(EchoApi.echoFallbackPool().stream(),
                EchoApi.replyFallbackPool().stream()).toList();
    }

    /**
     * 一行里<b>该被扫</b>的字面量。
     *
     * <h2>🔴 词表本身不算违规，词表的<b>替代文案</b>算</h2>
     *
     * <p>{@code CopyGuardFilter} 的职责就是<b>装着这些禁用词</b>：
     * {@code BANNED.put("它很失落", ...)} 的左边是<b>要拦的东西</b>，
     * 拿它当违规命中是把消防栓当火灾报出来。</p>
     *
     * <p>⚠️ 🔴 <b>但不能因此整个文件跳过</b> —— 恰恰相反，这个文件是最需要扫的：
     * {@code BANNED.put} 的<b>右边（替代文案）</b>此前就是
     * 「它一直在等一个和你说话的时刻」，🔴 <b>这道闸把一句制造内疚的话换成了另一句</b>，
     * 而换完之后下游不会再查它。</p>
     *
     * <p>所以规则是：{@code BANNED.put(k, v)} 这一行<b>只扫 {@code v}</b>；
     * {@code Pattern.compile(...)} 的检测正则整条跳过。其余一律照扫。</p>
     */
    static List<String> scannableLiteralsOf(String line) {
        List<String> lits = stringLiteralsOf(line);
        if (line.contains("Pattern.compile(")) {
            return List.of();      // 检测用的正则，不是文案
        }
        if (line.contains("BANNED.put(")) {
            // 只留替代文案（第 2 个字面量）；没有第 2 个说明替代是空串，无可扫
            return lits.size() >= 2 ? List.of(lits.get(1)) : List.of();
        }
        return lits;
    }

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"\\\\]*)\"");

    static List<String> stringLiteralsOf(String line) {
        List<String> out = new ArrayList<>();
        Matcher m = STRING_LITERAL.matcher(line);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   - " : "FAIL - ") + what);
        if (!ok) {
            failures++;
        }
    }
}
