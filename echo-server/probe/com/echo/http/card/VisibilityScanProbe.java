package com.echo.http.card;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 🔴 <b>{@code MemoryCard.visibilityIntent} 的裸读扫描</b> —— 让「不许直接读」这条纪律
 * 不再只靠注释撑着。
 *
 * <h2>要防的是什么</h2>
 *
 * <p>{@code C-5} 选了<b>读时取交集</b>：卡行保留作者意图，窗收窄时不级联改卡
 * （理由见 {@link CardVisibility}）。代价是 🔴 <b>库里长期存在「意图 = public
 * 但实际对外不可见」的卡行</b>，于是<b>单独读那一列永远偏宽</b>，
 * ⚠️ 🔴 <b>而偏宽的可见性判断就是内容泄漏</b>。</p>
 *
 * <p>纪律本身很简单：判「这张卡对外可见吗」必须走
 * {@link CardVisibility#effective}。问题是<b>它此前只写在注释里</b>，
 * 而漏掉它的表现不是报错，是多发了一条内容。</p>
 *
 * <h2>🔴 两道机制，一道编译期一道测试期</h2>
 *
 * <ol>
 *   <li><b>编译期（改名）</b>：字段从 {@code visibility} 改成 {@code visibilityIntent}。
 *       这一步做掉了三件事 —— 既有裸读全部编译断掉（由编译器列出，不靠人找）；
 *       名字自己说了它是<b>意图</b>而不是答案；🔴 <b>并且让检索第一次成为可能</b>：
 *       此前 {@code card.visibility} 与 {@code pet.visibility}（窗级，直接读是<b>对的</b>）
 *       在 grep 里长得一模一样，所以「扫出所有卡级裸读」这件事<u>做不到</u>。</li>
 *   <li><b>测试期（本探针）</b>：扫源码，每一处 {@code .visibilityIntent}
 *       都必须落在下面三类允许形态之一，否则红。</li>
 * </ol>
 *
 * <h2>⚠️ 这道机制的真实边界，不要高估它</h2>
 *
 * <p>🔴 <b>它拦不住的：</b></p>
 * <ul>
 *   <li><b>SQL 字符串里的 {@code visibility} 列</b> —— 扫描看不出一条
 *       {@code SELECT ... WHERE "visibility" = 'public'} 用得对不对；</li>
 *   <li><b>反射 / 序列化</b>整个绕过字段名；</li>
 *   <li>🔴 <b>白名单本身可以被人加一行绕过。</b></li>
 * </ul>
 *
 * <p>最后这条要说清楚：⚠️ <b>它不是漏洞，是这道机制唯一能提供的东西</b> ——
 * 🔴 <b>它把「忘了过 effective()」这种<u>看不见的遗漏</u>，变成了「往白名单里加一行」
 * 这种<u>必须写进 diff、评审时会被看到</u>的动作。</b>
 * 拦不住存心的人，但能拦住手快的人，而后者才是这类缺陷的实际来源。</p>
 */
public final class VisibilityScanProbe {

    static int failures = 0;

    /**
     * 🔴 白名单：<b>直接读意图是对的</b>那几处，每条都写清为什么。
     *
     * <p>按<b>整行原文</b>匹配而不是按行号 —— 行号会随上下文插入而漂移，
     * 漂移之后白名单就悄悄失配了（要么放过新的裸读，要么误报旧的）。</p>
     */
    static final Set<String> ALLOWED_LINES = Set.of(
            // 审核流水的 from/to：审核处置<b>不改</b>可见性，所以两端同值，
            // 记的就是「处置发生时作者设的是哪一档」。过 effective() 反而会把
            // 窗的状态混进一条讲审核的流水里。
            "cmd.fromVisibility = c.visibilityIntent;",
            "cmd.toVisibility = c.visibilityIntent;",
            // 处置时的卡面快照（§2.4 留痕）：它是 t_memory_card 那一行的<b>存档副本</b>，
            // 键名 visibility 对应的就是列名 visibility，忠实于行而不是忠实于「谁能看见」。
            "snap.put(\"visibility\", c.visibilityIntent);",
            // CAS 的期望值：比的就是「我读到的那一档还在不在」，必须是意图原值。
            // 过 effective() 会让窗一变动就 CAS 恒不命中。
            "if (!cardStore.changeVisibilityAtomically(cardId, me, card.visibilityIntent, to, clearPin,");

    /** 持久化与存储内部：赋值、CAS 比较、列读写。这一层<b>就是</b>那一列的搬运工。 */
    static final Set<String> STORE_FILES = Set.of(
            "InMemoryModerationStore.java", "PgModerationStore.java");

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src", "main", "java");
        check("源码目录存在（扫不到就等于恒过，那是假绿）cwd=" + Path.of("").toAbsolutePath(),
                Files.isDirectory(root));

        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        int accesses = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    String raw = lines.get(i);
                    String line = raw.strip();
                    if (!line.contains(".visibilityIntent")) {
                        continue;
                    }
                    // 注释里必须能引用这个字段名来解释纪律本身
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                        continue;
                    }
                    accesses++;
                    if (isAllowed(f, line)) {
                        continue;
                    }
                    offenders.add(f + ":" + (i + 1) + "\n           " + line);
                }
            }
        }

        // 🔴 断「确实扫到了访问点」而不只断「没有违规」：
        //    字段被改名 / 扫描路径写错时 accesses = 0，此时 offenders 也是空的，
        //    两种情况在 isEmpty() 上不可区分（同 BUILD-VERIFICATION §5：拒收当成功）
        check("扫到了源文件（" + scanned + " 个）", scanned > 100);
        check("🔴 扫到了 .visibilityIntent 的访问点（" + accesses + " 处）——"
                + "为 0 说明字段被改名或扫描路径失效，此时「无违规」是假的", accesses >= 15);

        if (!offenders.isEmpty()) {
            System.out.println("  🔴 未经允许的裸读：");
            offenders.forEach(o -> System.out.println("      " + o));
        }
        check("🔴 没有未经允许的 card.visibilityIntent 裸读（" + offenders.size() + " 处）",
                offenders.isEmpty());

        System.out.println(failures == 0 ? "\n=== 全部通过 ===" : "\n=== 🔴 " + failures + " 条失败 ===");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** 三类允许形态。 */
    static boolean isAllowed(Path file, String line) {
        // ① 喂给 CardVisibility 的判定函数 —— 这正是<b>正确</b>的用法
        if (line.contains("CardVisibility.")) {
            return true;
        }
        // ② 存储层的搬运：赋值 / CAS 比较 / 列读写
        String name = file.getFileName().toString();
        if (STORE_FILES.contains(name)
                && (line.contains("c.visibilityIntent =")
                || line.contains("card.visibilityIntent)")
                || line.contains("Objects.equals(c.visibilityIntent")
                || line.contains("String from = c.visibilityIntent;"))) {
            return true;
        }
        // ③ 逐条登记过理由的
        return ALLOWED_LINES.contains(line);
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   - " : "FAIL - ") + what);
        if (!ok) {
            failures++;
        }
    }
}
