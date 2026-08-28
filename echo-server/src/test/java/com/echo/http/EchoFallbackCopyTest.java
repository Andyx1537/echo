package com.echo.http;

import com.echo.http.safety.OutputSafetyGate;
import com.echo.http.safety.SafetyMetrics;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 兜底文案池的合规断言。
 *
 * <h2>为什么这个文件必须存在</h2>
 *
 * <p>{@code EchoApi.gateOrFallback} 的注释写着「兜底池里的句子是人工写好且已过闸的，
 * 所以回落是安全的终点」。<b>那句话是错的</b>，而且错的方向最坏：兜底池是
 * 唯一一条<b>绕过输出侧五关直达用户</b>的路径（闸拦下之后才回落到它），
 * 却是全链路里唯一没有任何机器检查的一段。</p>
 *
 * <p>实际漏出去的有两类：</p>
 * <ul>
 *   <li>「回头好像在<b>等你</b>笑」「<b>等你</b>有空来陪它坐一会儿」——
 *       同时踩 CR2（把它的状态归因于用户不在场）与 DP2（拉回访的诱导文案）；</li>
 *   <li>「它<b>在那边</b>，…」——{@code OutputSafetyGate} 第四关 B 组词面，
 *       而生成路径传的 {@code objectStatus} 是 {@code UNKNOWN}，B 组全拦。
 *       也就是说：同一句话模型生成出来会被拦，写死在兜底池里反而畅通无阻。</li>
 * </ul>
 *
 * <p>🔴 本测试<b>用生产路径同一个 {@code ObjectContext}</b>
 * （{@code EchoApi.fallbackObjectContext()}），而不是自己 new 一个宽松的上下文——
 * 后者会得到一个永远通过的假绿。</p>
 */
class EchoFallbackCopyTest {

    /**
     * 缺席归因 / 回访邀约的词面。
     *
     * <p>与 {@code HeuristicBotReviewer.ABSENCE_CUES} 同源（那份清单已经把「等你」
     * 列为缺席线索），这里只取其中<b>会出现在我们自己笔下</b>的那几个：
     * 评审器那份是用来打分的，这份是用来拒收的，所以宁严勿宽。</p>
     */
    private static final List<String> ABSENCE_OR_INVITE_CUES = List.of(
            "等你", "等着你", "在等", "等了很久", "你有空", "来看看它", "来陪它",
            "你多久没", "你好久没", "它很失落", "它很难过", "以为你", "不来了");

    private static OutputSafetyGate gate() {
        // 第一关（合规词表）未装配 → 跳过而非通过，其余四关照常。这与生产未配置时一致。
        return new OutputSafetyGate(new SafetyMetrics());
    }

    /**
     * 🔴 兜底池每一条都必须真的过得了五关。
     *
     * <p>这条断言的价值在于它<b>曾经是红的</b>：「它在那边，…」会被第四关 B 组拦下。</p>
     */
    @Test
    void everyFallbackLinePassesTheOutputGate() {
        OutputSafetyGate g = gate();
        for (String line : allFallbackLines()) {
            OutputSafetyGate.Verdict v = g.inspect(line, EchoApi.fallbackObjectContext());
            assertThat(v.passed())
                    .as("兜底文案会绕过安全闸直达用户，所以它自己必须过闸："
                            + "「%s」命中第 %s 关（%s）",
                            line,
                            v.gate() == null ? "-" : v.gate().order(),
                            v.matched())
                    .isTrue();
        }
    }

    /**
     * 🔴 CR2 + DP2：不得把它的状态归因于用户不在场，也不得写成回访邀约。
     *
     * <p>安全闸<b>查不出</b>这一类——五关拦的是丧失断言、拟真、注入与硬词，
     * 「等你有空来陪它坐一会儿」是一句温柔、通顺、不含任何禁用词的话，
     * 它踩的是产品调性红线，不是安全红线。所以必须单列一条。</p>
     */
    @Test
    void noFallbackLineBlamesTheUsersAbsenceOrInvitesThemBack() {
        for (String line : allFallbackLines()) {
            for (String cue : ABSENCE_OR_INVITE_CUES) {
                assertThat(line)
                        .as("CR2/DP2：文案不得把它的状态归因于你不在场，也不得拉回访；"
                                + "「%s」含「%s」", line, cue)
                        .doesNotContain(cue);
            }
        }
    }

    /** 兜底池不能是空的：{@code gateOrFallback} 会对它取随机下标。 */
    @Test
    void fallbackPoolsAreNotEmpty() {
        assertThat(EchoApi.echoFallbackPool()).isNotEmpty();
        assertThat(EchoApi.replyFallbackPool()).isNotEmpty();
    }

    /**
     * 🔴 全仓扫描：任何会下发给用户的中文字面量都不得含「等你」。
     *
     * <h2>为什么值得写成扫源码这么笨的形式</h2>
     *
     * <p>「等你」此前散在四处，其中两处是回声文案（明确踩 CR2/DP2），
     * 另外三处是错误提示（「这封信…也许它换了个地方等你」——那个「它」指的是信，
     * 严格说 CR2 未必踩到）。逐处判定的代价是：<b>以后每个写文案的人都要重新判一次
     * 「我这处算不算例外」</b>，而判错的那次不会有人发现。</p>
     *
     * <p>产品侧的裁定是<b>一并消掉，不留例外</b>——因为项目自己的
     * {@code HeuristicBotReviewer.ABSENCE_CUES} 已经把「等你」列为缺席线索，
     * 自家文案过不了自家闸这件事本身就要花掉后来每个人的时间。
     * 维护一条「一个都不许有」的规则，比维护一张例外清单便宜。</p>
     *
     * <p>只扫字符串字面量、跳过注释行：本文件与 {@code EchoApi} 的注释里都需要
     * <b>引用</b>这个词来解释为什么不能用它。</p>
     */
    @Test
    void noUserFacingCopyInMainSourcesSaysWaitingForYou() {
        Path root = Path.of("src", "main", "java");
        assertThat(Files.isDirectory(root))
                .as("扫不到源码目录就等于这条断言永远通过——那是假绿，宁可让它红。cwd=%s",
                        Path.of("").toAbsolutePath())
                .isTrue();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String trimmed = line.stripLeading();
                    // 注释行跳过：解释「为什么不能这么写」时必须能引用这个词
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")
                            || trimmed.startsWith("/*")) {
                        continue;
                    }
                    for (String literal : stringLiteralsOf(line)) {
                        if (literal.contains("等你") || literal.contains("等着你")) {
                            offenders.add(f + ":" + (i + 1) + "  " + literal.strip());
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new AssertionError("扫描 src/main/java 失败", e);
        }

        assertThat(offenders)
                .as("下发给用户的文案不得含「等你」（缺席线索，见 HeuristicBotReviewer.ABSENCE_CUES）。"
                        + "产品裁定：不留例外。命中处：\n%s", String.join("\n", offenders))
                .isEmpty();
    }

    /** 取出一行里所有双引号字面量的内容（够用即可：本仓没有含转义引号的中文文案）。 */
    private static List<String> stringLiteralsOf(String line) {
        List<String> out = new ArrayList<>();
        Matcher m = STRING_LITERAL.matcher(line);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"\\\\]*)\"");

    private static List<String> allFallbackLines() {
        return java.util.stream.Stream
                .concat(EchoApi.echoFallbackPool().stream(), EchoApi.replyFallbackPool().stream())
                .toList();
    }
}
