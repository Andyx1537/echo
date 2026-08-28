package com.echo.iou;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 欠条检查（{@code PRODUCT-SKELETON §5.4} 的机械判据）。
 *
 * <h2>判据一句话</h2>
 *
 * <p>🔴 <b>一个字段只有写没有读，或者只有读没有写，就是一张没还的欠条。</b></p>
 *
 * <p>它要治的病不是「赶工漏了」，而是<b>建造顺序反了必然长出来的替身</b>：先造
 * 「一张卡被看见之后会发生的所有事」，把「造出这张卡」留到最后，倒着建的每一步都得
 * 假设上游产物存在，于是每条线都造了替身。🔴 <b>替身一到位，「上游不存在」这件事就不再有
 * 任何症状</b> —— 卡有了、流里有东西、审核队列里有工单、测试全绿。
 * ⚠️ <b>缺口是被盖住了，不是被记下了。</b></p>
 *
 * <h2>为什么这条能自动化</h2>
 *
 * <p>「只写不读 / 只读不写」是一个<b>静态可判的性质</b>，不需要人去感觉。这和
 * {@code BindingGuardProbe} 里那条「谁提 {@code BINDING_REQUIRED} 谁就必须用到
 * {@code BindingGuard}」是同一类东西：<b>把靠人记得的事变成机器检查。</b></p>
 *
 * <h2>🔴 检查设施自己必须先被检查</h2>
 *
 * <p>本探针<b>先跑自检</b>（{@link #selfCheck()}）：每一类检查都喂一个<b>真欠条样本</b>
 * （必须被抓到）和一个<b>正常样本</b>（不许误报）。⚠️ 少了这一步，
 * 「扫不到东西」和「没有欠条」在输出上长得一模一样 —— 🔴 <b>那正是假绿。</b>
 * 所以还额外断言了「确实扫到了 N 个文件 / M 张表」，零结果一律判失败。</p>
 *
 * <h2>判定口径（写清楚，因为口径本身会被质疑）</h2>
 *
 * <ul>
 *   <li><b>写证据</b>：列出现在 {@code INSERT INTO "t" (...)} 的列表里，
 *       或 {@code UPDATE "t" SET} 到 {@code WHERE} 之间。</li>
 *   <li><b>读证据</b>：列出现在 {@code SELECT} 语句里（列表、{@code WHERE}、
 *       {@code ORDER BY} 都算），或出现在结果集取值处
 *       （{@code rs.getString("col")} / {@code r.get("col")}）。</li>
 *   <li>🔴 <b>{@code SELECT *} 本身不算读证据。</b>⚠️ 这一条是关键：本仓大量用
 *       {@code SELECT *}，把它当成「读了所有列」的话，
 *       <b>C1（{@code anonymous} 全仓无人读）就会被它盖住</b> ——
 *       而「把行搬进内存、没有任何人消费」正是欠条本身的形状。
 *       真正的读证据是<b>有人把这一列取出来用</b>。</li>
 *   <li>{@code INSERT} 语句里的 {@code ON CONFLICT (...)} 列<b>只算写、不算读</b>：
 *       它是写入语义的一部分，不是消费者。</li>
 * </ul>
 *
 * <h2>已知的口径宽窄（不是 bug，是取舍）</h2>
 *
 * <p>读证据的归属做得<b>偏宽</b>（一条语句里的列名记给该语句涉及的所有表；
 * {@code get*("col")} 记给同文件提到的所有表）。⚠️ 宽 = <b>宁可漏报，不肯误报</b>：
 * 一条会喊狼来了的检查活不过三天。🔴 漏报的那部分写在 {@link #blindSpots()} 里，
 * <b>不要以为绿了就没欠条。</b></p>
 */
public final class IouProbe {

    static int failures = 0;

    /** 表名 → 列名（含 ALTER TABLE ADD COLUMN 补的列）。 */
    static Map<String, Set<String>> schema = new TreeMap<>();

    /** {@code CHECK (... IN (...))} 声明的合法取值：{@code 表.列} → 取值集合。 */
    static Map<String, Set<String>> checkValues = new TreeMap<>();

    /** Java 源码：文件路径 → 归一化后的正文（拼接串已粘合、{@code \"} 已还原）。 */
    static Map<String, String> code = new LinkedHashMap<>();

    /** 前端源码：文件路径 → 原文。 */
    static Map<String, String> web = new LinkedHashMap<>();

    /** 对外契约正文（{@code docs/API-CONTRACT.md}）—— 只读，用来判「这个字段有没有约定的消费者」。 */
    static String contract = "";

    /**
     * ORM 绑定：表名 → 实体类名。
     *
     * <p>🔴 <b>这一层是第一版漏掉的，而漏掉它会让检查<u>说假话</u>。</b>
     * {@code com.echo.module.*} 走的是注解 + 反射的 class table
     * （{@code @Table(name = "t_avatar")} + {@code XxxRepository}），
     * <b>全程没有一行 SQL 字符串</b>。⚠️ 只按 SQL 找证据的话，这 9 张表会被判成
     * 「代码里既不读也不写」—— 而它们其实有完整的读写通道。</p>
     *
     * <p>📌 一条会说假话的检查比没有检查更坏：它会让人不再相信真的那几条。
     * 所以 ORM 表改判成另一个问题：<b>仓储有没有人调用</b>。</p>
     */
    static Map<String, String> ormTables = new TreeMap<>();

    public static void main(String[] args) {
        System.out.println("== 欠条检查（PRODUCT-SKELETON §5.4：只写不读 / 只读不写 = 没还的欠条）==");

        selfCheck();
        loadSchema();
        loadCode();
        loadWeb();
        loadContract();

        List<Iou> ious = new ArrayList<>();
        ious.addAll(checkTables());
        ious.addAll(checkColumns());
        ious.addAll(checkRoundTripFields());
        ious.addAll(checkCheckConstraintValues());
        ious.addAll(checkWireFields());

        report(ious);
        knownFour(ious);
        census(ious);
        blindSpots();

        System.out.println();
        if (failures > 0) {
            System.out.println("=== 🔴 " + failures + " 条失败 ===");
            System.exit(1);
        }
        System.out.println("=== 全部通过 ===");
    }

    // ================================================================= 数据结构

    /** 一张欠条。{@code key} 要稳定可检索，回执和后续核对都靠它。 */
    record Iou(String kind, String key, String detail) {
        @Override
        public String toString() {
            return "[" + kind + "] " + key + " —— " + detail;
        }
    }

    static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "ok   - " : "FAIL - ") + name);
    }

    // =================================================================== 装载

    static void loadSchema() {
        Path f = Path.of("src", "main", "resources", "sql", "schema.sql");
        check("schema.sql 找得到（找不到就等于后面全部恒过）", Files.isRegularFile(f));
        String sql = read(f);
        schema = parseSchema(sql);
        checkValues = parseCheckValues(sql);
        // 🔴 零结果不可信：表名有三种引号写法，解析器没跟上时这里会静静地变成 0 张表
        check("解析出的表数量像话（" + schema.size() + " 张，期望 >= 30）", schema.size() >= 30);
        int cols = schema.values().stream().mapToInt(Set::size).sum();
        check("解析出的列数量像话（" + cols + " 列，期望 >= 200）", cols >= 200);
        check("解析出 CHECK 取值约束（" + checkValues.size() + " 组，期望 >= 5）",
                checkValues.size() >= 5);
    }

    static void loadCode() {
        code = readTree(Path.of("src", "main", "java"), ".java");
        check("扫到 Java 源文件（" + code.size() + " 个，期望 >= 100）", code.size() >= 100);
        ormTables = parseOrmTables(code);
        // 🔴 零结果不可信：注解写法一变（换行、加空格）这里就会静静地变成 0
        check("扫到 ORM 注解绑的表（" + ormTables.size() + " 张，期望 >= 5）", ormTables.size() >= 5);
    }

    /** 从 {@code @Table(name = "t_x")} 认出 ORM 绑的表，实体类名取所在文件名。 */
    static Map<String, String> parseOrmTables(Map<String, String> sources) {
        Map<String, String> out = new TreeMap<>();
        Pattern p = Pattern.compile("@Table\\s*\\(\\s*name\\s*=\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"");
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Matcher m = p.matcher(e.getValue());
            while (m.find()) {
                String file = e.getKey();
                String cls = file.substring(file.lastIndexOf('/') + 1).replace(".java", "");
                out.put(m.group(1), cls);
            }
        }
        return out;
    }

    static void loadContract() {
        Path f = Path.of("..", "docs", "API-CONTRACT.md");
        if (!Files.isRegularFile(f)) {
            check("🔴 API-CONTRACT.md 找得到（找不到则契约字段那一类判不准，不能当成没问题）", false);
            return;
        }
        contract = read(f);
        check("读到对外契约正文（" + contract.length() / 1000 + "k 字符，期望 >= 50k）",
                contract.length() >= 50_000);
    }

    static void loadWeb() {
        Path root = Path.of("..", "echo-h5-proto", "src");
        if (!Files.isDirectory(root)) {
            // 🔴 前端不在原地时必须把「查不了」说出来，不能静静地当成「没问题」
            check("🔴 前端源码目录找得到（找不到则契约字段那一类检查形同不存在）", false);
            return;
        }
        web = readTree(root, ".ts");
        web.putAll(readTree(root, ".tsx"));
        check("扫到前端源文件（" + web.size() + " 个，期望 >= 50）", web.size() >= 50);
    }

    // ============================================================= 检查一：表级

    /**
     * 表级：只写不读 / 只读不写 / 建了但代码里既不读也不写。
     *
     * <p>📌 表级先判，是因为它<b>不需要列归属</b>：一张表全仓只有 {@code INSERT}、
     * 一条 {@code SELECT} 都没有，这个判断没有任何歧义。
     * 🔴 C8（曝光表只写不读）和 C12（卡级回响没有任何 SQL）就在这一层被抓住。</p>
     */
    static List<Iou> checkTables() {
        List<Iou> out = new ArrayList<>();
        for (String table : schema.keySet()) {
            boolean written = false;
            boolean read = false;
            for (String src : code.values()) {
                if (hasStatement(src, table, Kind.WRITE)) {
                    written = true;
                }
                if (hasStatement(src, table, Kind.READ)) {
                    read = true;
                }
            }
            // ORM 绑的表：读写通道由仓储生成，SQL 里找不到。改判「仓储有没有人调用」。
            String entity = ormTables.get(table);
            if (entity != null && !written && !read) {
                String repo = entity + "Repository";
                long callers = code.entrySet().stream()
                        .filter(e -> !e.getKey().endsWith("/" + repo + ".java"))
                        .filter(e -> e.getValue().matches("(?s).*\\b" + Pattern.quote(repo) + "\\b.*"))
                        .count();
                if (callers == 0) {
                    out.add(new Iou("仓储·无人调用", table + " ← " + repo,
                            "表和仓储都在，全仓没有任何一处调用这个仓储 —— 通道通着，两头没接上"));
                }
                continue;
            }
            if (written && !read) {
                out.add(new Iou("表·只写不读", table,
                        "有 INSERT/UPDATE/DELETE，全仓没有一条 SELECT —— 数据在涨，没有任何代码说它写给谁看"));
            } else if (read && !written) {
                out.add(new Iou("表·只读不写", table,
                        "有 SELECT，全仓没有任何写入方 —— 读回来的永远是空的"));
            } else if (!read && !written) {
                out.add(new Iou("表·全死", table,
                        "schema 里建了，代码里既不读也不写 —— 建表这个动作本身就是一张欠条"));
            }
        }
        return out;
    }

    // ============================================================= 检查二：列级

    /**
     * 列级：读写都活着的表里，哪些列是单向的。
     *
     * <p>🔴 C1（{@code t_flower_log.anonymous} 每次都写库、全仓无人读）在这一层。
     * ⚠️ 它能被抓到的前提是「{@code SELECT *} 不算读证据」——
     * 否则同表其他列的 {@code SELECT *} 会把它一起盖住。</p>
     */
    static List<Iou> checkColumns() {
        List<Iou> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : schema.entrySet()) {
            String table = e.getKey();
            // 🔴 ORM 绑的表不做列级判定：列是注解映射的，SQL 里根本没有列名，
            //    在这里下任何结论都是编的。⚠️ 这块漏报已写进 blindSpots()。
            if (ormTables.containsKey(table)) {
                continue;
            }
            Set<String> written = new TreeSet<>();
            Set<String> read = new TreeSet<>();
            for (Map.Entry<String, String> f : code.entrySet()) {
                String src = f.getValue();
                written.addAll(columnsIn(src, table, Kind.WRITE));
                read.addAll(columnsIn(src, table, Kind.READ));
                if (mentionsTable(src, table)) {
                    read.addAll(extractedColumns(src));
                }
            }
            if (written.isEmpty() && read.isEmpty()) {
                continue;   // 整张表的情况已在表级报过，不重复计一遍
            }
            for (String col : e.getValue()) {
                boolean w = written.contains(col);
                boolean r = read.contains(col);
                if (w && !r) {
                    out.add(new Iou("列·只写不读", table + "." + col,
                            "进了 INSERT/UPDATE，没有任何一处把它取出来用"));
                } else if (r && !w) {
                    out.add(new Iou("列·只读不写", table + "." + col,
                            "有人读它，没有任何一处写它 —— 读到的永远是默认值"));
                } else if (!w && !r) {
                    // 🔴 这一支是验红时才发现漏掉的：造一张「只写不读」的假欠条去撞检查，
                    //    它没变红 —— 因为那一列压根没进任何 SQL，两侧证据都是空，
                    //    而当时的代码只判「一侧有、另一侧没有」，两侧都没有的直接跳过了。
                    // ⚠️ 「两侧都没有」不是没问题，它是欠条里最彻底的一种：
                    //    列建在库里，代码从头到尾没提过它。
                    out.add(new Iou("列·两侧皆无", table + "." + col,
                            "同表其他列在用，这一列读写两侧都没有任何一处提到 —— 建了就没管过"));
                }
            }
        }
        return out;
    }

    // ========================================================= 检查三：回读闭环

    /**
     * 🔴 <b>回读闭环</b>：一个字段唯一的赋值来源是「从库里读出来」，而它又被写回库。
     *
     * <h2>为什么这一类必须单独查</h2>
     *
     * <p>⚠️ 这种字段在前两层检查里<b>读写都齐全，完全正常</b>：INSERT 里有它、
     * {@code rs.getString} 也有它。但它的值<b>没有源头</b> —— 库→字段→库，
     * 全程没有任何一处把真实内容放进去。🔴 <b>所以它永远是空的，而且不报错。</b></p>
     *
     * <p>📌 C9（卡的主题字段只有读回、没有任何写入方）就是这个形状：
     * {@code topicIdsJson} 唯一的赋值是 {@code rs.getString("topicIds")}。</p>
     */
    static List<Iou> checkRoundTripFields() {
        List<Iou> out = new ArrayList<>();
        // 字段名 → 赋值右侧是否只有结果集取值
        Map<String, Boolean> onlyFromDb = new LinkedHashMap<>();
        Map<String, String> where = new LinkedHashMap<>();
        Pattern assign = Pattern.compile(
                "\\b(?:[A-Za-z_][A-Za-z0-9_]*\\.)?([a-z][A-Za-z0-9_]*)\\s*=\\s*([^;\\n]{0,200});");
        for (Map.Entry<String, String> f : code.entrySet()) {
            Matcher m = assign.matcher(f.getValue());
            while (m.find()) {
                String field = m.group(1);
                String rhs = m.group(2);
                boolean fromDb = rhs.matches(".*\\b(?:rs|r|row)\\.get[A-Za-z]*\\(\".*")
                        || rhs.matches(".*\\bas(?:Str|Long|Bool|Int)\\(\\s*r\\.get\\(\".*");
                Boolean prev = onlyFromDb.get(field);
                onlyFromDb.put(field, prev == null ? fromDb : (prev && fromDb));
                if (fromDb) {
                    where.put(field, f.getKey());
                }
            }
        }
        for (Map.Entry<String, Boolean> e : onlyFromDb.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue())) {
                continue;
            }
            String field = e.getKey();
            // 还要确认它真的被写回库了，否则只是一个纯读字段，属于别的形状
            boolean writtenBack = code.values().stream()
                    .anyMatch(s -> s.matches("(?s).*\\bset[A-Za-z]+\\(\\s*\\d+\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*\\."
                            + Pattern.quote(field) + "\\b.*"));
            if (writtenBack) {
                out.add(new Iou("字段·回读闭环", field,
                        "唯一赋值来源是从库里读出来（" + where.get(field)
                                + "），又被写回库 —— 值没有源头，永远是空的"));
            }
        }
        return out;
    }

    // ======================================================= 检查四：枚举取值

    /**
     * {@code CHECK (... IN ('a','b','c'))} 声明的取值里，哪些没有任何代码路径写入。
     *
     * <p>📌 「定义了但没人写」和「写了但没人读」是同一张欠条的两面：
     * schema 认得这个状态，而系统永远到不了它。</p>
     */
    static List<Iou> checkCheckConstraintValues() {
        List<Iou> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : checkValues.entrySet()) {
            for (String value : e.getValue()) {
                String literal = "\"" + value + "\"";
                boolean appears = code.values().stream().anyMatch(s -> s.contains(literal));
                if (!appears) {
                    out.add(new Iou("取值·无人写入", e.getKey() + " = '" + value + "'",
                            "schema 认这个取值，全仓 Java 没有任何一处出现过它"));
                }
            }
        }
        return out;
    }

    // ======================================================= 检查五：契约字段

    /**
     * 对外契约字段：服务端下发了，但<b>既没有实现方消费、也没有约定说谁该消费</b>。
     *
     * <h2>🔴 判准为什么是「前端 + 契约文档」两个都不认</h2>
     *
     * <p>第一版只拿前端当消费者，结果 70 多个运营侧字段（审核工单、申诉、快照）全部命中 ——
     * ⚠️ <b>它们不是欠条，是消费者还没建（运营台不在这个仓里）。</b>
     * 那样的清单会把真的那几条淹掉。</p>
     *
     * <p>📌 所以消费者认两种：<b>前端源码里出现过</b>（实现方要它），
     * 或 <b>{@code API-CONTRACT.md} 里出现过</b>（有约定说谁该要它）。
     * 🔴 <b>两个都不认的才是欠条</b>：没人在用，也没有任何一处约定说它是给谁的。</p>
     *
     * <p>⚠️ 反方向（前端在读而服务端从不下发）在这一层<b>做不准</b>：
     * 前端有 mock 后端，它自己就会造出服务端没有的字段，那不是欠条。
     * 反方向写进 {@link #blindSpots()}。</p>
     */
    static List<Iou> checkWireFields() {
        List<Iou> out = new ArrayList<>();
        if (web.isEmpty() || contract.isEmpty()) {
            return out;   // 判不准时不出结论；「查不了」已经在装载处报成失败
        }
        Set<String> emitted = new TreeSet<>();
        Pattern put = Pattern.compile("\\.put\\(\"([a-z][A-Za-z0-9_]*)\"\\s*,");
        for (String src : code.values()) {
            Matcher m = put.matcher(src);
            while (m.find()) {
                emitted.add(m.group(1));
            }
        }
        check("扫到服务端下发的字段名（" + emitted.size() + " 个，期望 >= 50）", emitted.size() >= 50);
        String allWeb = String.join("\n", web.values());
        for (String field : emitted) {
            if (mentions(allWeb, field) || mentions(contract, field)) {
                continue;
            }
            out.add(new Iou("契约·下发无人要", field,
                    "服务端 put 到响应里，前端 " + web.size() + " 个源文件与 API-CONTRACT 都没提过它"));
        }
        return out;
    }

    /** 字段名以「独立标识符」的形式出现过（不是别的词的一截）。 */
    static boolean mentions(String haystack, String field) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(field) + "(?![A-Za-z0-9_])")
                .matcher(haystack).find();
    }

    // ==================================================================== 汇总

    static void report(List<Iou> ious) {
        System.out.println("\n---- 欠条清单（" + ious.size() + " 张）----");
        Map<String, List<Iou>> byKind = new LinkedHashMap<>();
        for (Iou i : ious) {
            byKind.computeIfAbsent(i.kind(), k -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<String, List<Iou>> e : byKind.entrySet()) {
            System.out.println("\n  【" + e.getKey() + "】" + e.getValue().size() + " 张");
            for (Iou i : e.getValue()) {
                System.out.println("    · " + i.key() + " —— " + i.detail());
            }
        }
    }

    /**
     * 🔴 拿骨架总账已经点名的四条来验这条检查本身。
     *
     * <p>⚠️ 抓不到它们说明检查有洞，而这种洞在「清单看起来挺长」的时候<b>完全看不出来</b>。
     * 📌 所以这四条是<b>检查器的回归用例</b>，不是产品结论。</p>
     */
    static void knownFour(List<Iou> ious) {
        System.out.println("\n---- 🔴 已知四处（抓不到说明检查有洞）----");
        check("C12 卡级回响 t_resonance —— 抓到", hits(ious, "t_resonance"));
        check("C1  anonymous —— 抓到", hits(ious, "anonymous"));
        check("C8  曝光表 t_card_exposure —— 抓到", hits(ious, "t_card_exposure"));
        check("C9  主题（t_topic / topicIds）—— 抓到",
                hits(ious, "t_topic") || hits(ious, "topicId"));
    }

    static boolean hits(List<Iou> ious, String needle) {
        List<Iou> matched = ious.stream().filter(i -> i.key().contains(needle)).toList();
        matched.forEach(i -> System.out.println("       ↳ " + i));
        return !matched.isEmpty();
    }

    /**
     * 欠条总量的上限。🔴 <b>这个数只许往下调。</b>
     *
     * <h2>为什么是一个数，而不是一张逐条的清单</h2>
     *
     * <p>逐条记下「这 77 张已知、允许存在」＝ 一张例外表，⚠️ 而<b>例外表一长这条检查就废了</b>：
     * 下一个人添了欠条，顺手往表里加一行就绿了，那正是本轮要治的病。</p>
     *
     * <p>📌 一个上限没有这个毛病：<b>加欠条会红，还欠条不会红</b>，
     * 想放宽只能改这个数，而改它是一次显式的、会被 diff 看到的动作。</p>
     *
     * <p>2026-08-27 首次普查：77 张。</p>
     */
    static final int CENSUS = 77;

    static void census(List<Iou> ious) {
        System.out.println("\n---- 欠条总量 ----");
        int n = ious.size();
        check("🔴 欠条没有变多（现 " + n + " 张，上限 " + CENSUS + "）—— 多了就是又欠了一张",
                n <= CENSUS);
        if (n < CENSUS) {
            System.out.println("       📌 少了 " + (CENSUS - n)
                    + " 张，说明有人还了欠条。把 CENSUS 调到 " + n + "，别让上限留在虚高的位置。");
        }
    }

    static void blindSpots() {
        System.out.println("""

                ---- 🔴 这条检查抓不到什么（不写下来，下一个人会以为绿了就没欠条）----
                  1. 🔴 读写都齐全、而写进去的值本身就是替身 —— 本类抓不到，也抓不了。
                     真实例子：ModerationStore.putCard 自述「发布路径的最小替身」。
                     卡有 INSERT 有 SELECT，每一列都两侧齐全，这条检查一片绿；
                     而「作者把回忆发布成一张卡」这个动作全仓不存在。⚠️ 替身满足了本检查的
                     全部形式要求 —— 它就是为了让下游不报错才被造出来的。
                  2. 前端在读而服务端不下发：前端有 mock 后端，它自己会造出字段，判不准
                  3. 表内跨列语义错配（数字张冠李戴那类）：每一列都有读有写，错在聚合口径
                  4. 读证据归属偏宽（同文件的 get("col") 记给该文件提到的所有表）→ 会漏报
                  5. ORM 绑的 9 张表只做了表级判定，列级一律跳过（列名在注解里，SQL 里没有）
                  6. 只看得见「已经建了的东西」。🔴 端点根本不存在（如 /export）时，
                     没有列、没有字段、没有取值 —— 什么都不写就什么都不欠，这条检查一个字都不会说
                """);
    }

    // ================================================================ 自检样本

    /**
     * 🔴 正反自检：真欠条必须被抓到，正常字段不许误报。
     *
     * <p>⚠️ 这一节要跑在真扫描之前。少了它，「解析器坏掉扫出 0 条」会被读成
     * 「恭喜，没有欠条」—— <b>而这正是本轮要治的那个病本身。</b></p>
     */
    static void selfCheck() {
        System.out.println("\n---- 🔴 检查器自检（先证明它抓得到，再相信它的结果）----");

        // ---- schema 解析 ----
        String sampleSchema = """
                CREATE TABLE IF NOT EXISTS "t_demo" (
                    "id"      bigint NOT NULL,
                    "written" smallint NOT NULL DEFAULT 0,
                    "healthy" varchar(16) NOT NULL DEFAULT '',
                    CONSTRAINT "t_demo_ck" CHECK ("healthy" IN ('alive','ghost'))
                );
                ALTER TABLE "t_demo" ADD COLUMN IF NOT EXISTS "added" bigint;
                """;
        Map<String, Set<String>> parsed = parseSchema(sampleSchema);
        check("✅ 自检：解析出表名 t_demo", parsed.containsKey("t_demo"));
        check("✅ 自检：列都解出来了（含 ALTER 补的 added）",
                parsed.getOrDefault("t_demo", Set.of())
                        .containsAll(Set.of("id", "written", "healthy", "added")));
        check("✅ 自检：CHECK 取值解出来了",
                parseCheckValues(sampleSchema).getOrDefault("t_demo.healthy", Set.of())
                        .containsAll(Set.of("alive", "ghost")));

        // ---- 写/读证据 ----
        String writeOnly = normalize("""
                String sql = "INSERT INTO \\"t_demo\\" (\\"id\\",\\"written\\") VALUES (?,?)";
                """);
        check("🔴 自检·真欠条：只有 INSERT 的表被判成有写、无读",
                hasStatement(writeOnly, "t_demo", Kind.WRITE)
                        && !hasStatement(writeOnly, "t_demo", Kind.READ));
        check("🔴 自检·真欠条：INSERT 里的列被记成写证据",
                columnsIn(writeOnly, "t_demo", Kind.WRITE).contains("written"));
        check("🔴 自检·不误报：INSERT 里的列不被记成读证据",
                !columnsIn(writeOnly, "t_demo", Kind.READ).contains("written"));

        String healthy = normalize("""
                String ins = "INSERT INTO \\"t_demo\\" (\\"id\\",\\"healthy\\") VALUES (?,?)";
                String sel = "SELECT \\"healthy\\" FROM \\"t_demo\\" WHERE \\"id\\" = ?";
                """);
        check("✅ 自检·不误报：读写都有的列，两侧证据都成立",
                columnsIn(healthy, "t_demo", Kind.WRITE).contains("healthy")
                        && columnsIn(healthy, "t_demo", Kind.READ).contains("healthy"));

        // ---- 🔴 SELECT * 不算读证据 ----
        String star = normalize("""
                String ins = "INSERT INTO \\"t_demo\\" (\\"id\\",\\"written\\") VALUES (?,?)";
                String sel = "SELECT * FROM \\"t_demo\\" WHERE \\"id\\" = ?";
                """);
        check("🔴 自检·关键口径：SELECT * 不把 written 记成读（否则 C1 会被盖住）",
                !columnsIn(star, "t_demo", Kind.READ).contains("written"));
        check("✅ 自检·不误报：SELECT * 语句里 WHERE 用到的 id 仍算读",
                columnsIn(star, "t_demo", Kind.READ).contains("id"));
        check("✅ 自检：SELECT * 仍然让这张表算「被读过」（表级不误报成只写不读）",
                hasStatement(star, "t_demo", Kind.READ));

        // ---- 结果集取值算读 ----
        check("✅ 自检：rs.getString(\"x\") 算读证据",
                extractedColumns("String v = rs.getString(\"x\");").contains("x"));
        check("✅ 自检：r.get(\"y\") 算读证据",
                extractedColumns("p.a = asLong(r.get(\"y\"));").contains("y"));
        check("🔴 自检·不误报：普通 map.put(\"z\") 不算读证据",
                !extractedColumns("m.put(\"z\", 1);").contains("z"));

        // ---- 🔴 ORM 注解（第一版漏了这层，导致检查说了假话）----
        Map<String, String> orm = parseOrmTables(Map.of(
                "com/echo/module/demo/Widget.java",
                "@Table(name = \"t_widget\", comment = \"零件\")\npublic class Widget {}"));
        check("🔴 自检：@Table 绑的表被认出来（漏了这层，9 张 ORM 表会被误报成「全死」）",
                "Widget".equals(orm.get("t_widget")));
        check("🔴 自检·不误报：注解换行加空格也认得出",
                "Widget".equals(parseOrmTables(Map.of("a/Widget.java",
                        "@Table(\n    name = \"t_widget\",\n    comment = \"x\")")).get("t_widget")));

        // ---- 契约字段的「独立标识符」判定 ----
        check("✅ 自检：字段名整词出现算有消费者", mentions("const x = card.topicIds", "topicIds"));
        check("🔴 自检·不误报：只是别的词的一截，不算",
                !mentions("const y = subTopicIdsExtra", "topicIds"));

        // ---- 字符串粘合与 CRLF ----
        check("🔴 自检：跨行拼接的 SQL 被粘成一条（不粘的话后半句列全丢）",
                normalize("\"SELECT \\\"a\\\" FROM \" + \"\\\"t_demo\\\"\"")
                        .contains("SELECT \"a\" FROM \"t_demo\""));
        check("🔴 自检·CRLF：带 \\r\\n 的源码解析结果与 \\n 版一致",
                parseSchema(sampleSchema.replace("\n", "\r\n")).equals(parsed));
    }

    // ================================================================== 引擎

    enum Kind { READ, WRITE }

    static final Pattern IDENT = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\"");

    /**
     * 归一化 Java 正文：把 {@code "a" + "b"} 粘成 {@code "ab"}，再把 {@code \"} 还原成 {@code "}。
     *
     * <p>🔴 不粘合的话，跨行拼接的 SQL 会被切成两半，后半句里的列名全部凭空消失 ——
     * 而那种「扫不到」在输出上和「没有欠条」一模一样。</p>
     */
    static String normalize(String raw) {
        String s = raw.replaceAll("\"\\s*\\+\\s*\"", "");
        return s.replace("\\\"", "\"");
    }

    /** 这个文件里有没有针对该表的读/写语句。 */
    static boolean hasStatement(String src, String table, Kind kind) {
        return !statements(src, table, kind).isEmpty();
    }

    /**
     * 取出针对某张表的语句窗口。
     *
     * <p>窗口边界取「到下一条语句关键字或 {@code ;} 为止」，最长 1500 字符 ——
     * SQL 是拼在 Java 串里的，没有可靠的语句终止符，所以只能取窗口。
     * ⚠️ 取窄了会丢列，取宽了会把邻句的列算进来；1500 是按本仓最长的那条 SQL 定的。</p>
     */
    static List<String> statements(String src, String table, Kind kind) {
        List<String> out = new ArrayList<>();
        String quoted = "\"" + table + "\"";
        Pattern head = kind == Kind.WRITE
                ? Pattern.compile("(?i)(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+" + Pattern.quote(quoted))
                : Pattern.compile("(?i)SELECT\\b");
        Matcher m = head.matcher(src);
        while (m.find()) {
            int start = m.start();
            int end = Math.min(src.length(), start + 1500);
            String window = src.substring(start, end);
            int stop = nextStatementBoundary(window);
            if (stop > 0) {
                window = window.substring(0, stop);
            }
            if (kind == Kind.READ) {
                // SELECT 没法从头部锚定表名（表名在 FROM/JOIN 里），所以窗口内必须提到这张表
                if (!window.contains(quoted)) {
                    continue;
                }
            }
            out.add(window);
        }
        return out;
    }

    static int nextStatementBoundary(String window) {
        Pattern p = Pattern.compile("(?i);|\\b(INSERT\\s+INTO|UPDATE\\s+\"|DELETE\\s+FROM)\\b");
        Matcher m = p.matcher(window);
        // 从 1 开始找，别把窗口自己的头部当成边界
        if (m.find(1)) {
            return m.start();
        }
        return -1;
    }

    /** 该表在这个文件里出现过（表名带引号）。 */
    static boolean mentionsTable(String src, String table) {
        return src.contains("\"" + table + "\"");
    }

    /**
     * 某张表在这个文件里的读/写列证据。
     *
     * <ul>
     *   <li>{@code INSERT}：表名到 {@code VALUES} 之间的列名 = 写。
     *       ⚠️ 整条 {@code INSERT} 都不产生读证据（{@code ON CONFLICT} 的列是写语义的一部分）。</li>
     *   <li>{@code UPDATE}：{@code SET} 到 {@code WHERE} 之间 = 写；{@code WHERE} 之后 = 读。</li>
     *   <li>{@code SELECT}：🔴 {@code SELECT *} 的 {@code *} 不产生列读证据，
     *       其余位置（列表、{@code WHERE}、{@code ORDER BY}）的列名 = 读。</li>
     * </ul>
     */
    static Set<String> columnsIn(String src, String table, Kind kind) {
        Set<String> out = new LinkedHashSet<>();
        String quoted = "\"" + table + "\"";
        if (kind == Kind.WRITE) {
            for (String st : statements(src, table, Kind.WRITE)) {
                String upper = st.toUpperCase(Locale.ROOT);
                String scope = st;
                if (upper.startsWith("INSERT")) {
                    int v = upper.indexOf("VALUES");
                    scope = v > 0 ? st.substring(0, v) : st;
                } else if (upper.startsWith("UPDATE")) {
                    int set = upper.indexOf(" SET ");
                    int wh = upper.indexOf(" WHERE ");
                    if (set > 0) {
                        scope = st.substring(set, wh > set ? wh : st.length());
                    }
                } else {
                    continue;   // DELETE 不写列
                }
                out.addAll(identsIn(scope, table));
            }
            return out;
        }
        for (String st : statements(src, table, Kind.READ)) {
            out.addAll(identsIn(st, table));
        }
        // UPDATE 的 WHERE 段也是读
        for (String st : statements(src, table, Kind.WRITE)) {
            String upper = st.toUpperCase(Locale.ROOT);
            if (!upper.startsWith("UPDATE")) {
                continue;
            }
            int wh = upper.indexOf(" WHERE ");
            if (wh > 0) {
                out.addAll(identsIn(st.substring(wh), table));
            }
        }
        out.remove(table);
        return out;
    }

    static Set<String> identsIn(String sqlFragment, String table) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = IDENT.matcher(sqlFragment);
        while (m.find()) {
            String id = m.group(1);
            if (!id.equals(table) && !schema.containsKey(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** 结果集取值：{@code rs.getString("col")} / {@code r.get("col")} —— 这才是真的「有人用它」。 */
    static Set<String> extractedColumns(String src) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\b(?:rs|r|row|rows)\\.get[A-Za-z]*\\(\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"")
                .matcher(src);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    // ============================================================== schema 解析

    static Map<String, Set<String>> parseSchema(String sql) {
        Map<String, Set<String>> out = new TreeMap<>();
        Matcher t = Pattern.compile("(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?\"([^\"]+)\"\\s*\\((.*?)\\n\\s*\\);")
                .matcher(sql);
        while (t.find()) {
            String table = t.group(1);
            Set<String> cols = out.computeIfAbsent(table, k -> new TreeSet<>());
            for (String line : t.group(2).split("\\r?\\n")) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("--") || s.startsWith("CONSTRAINT")
                        || s.startsWith("PRIMARY KEY") || s.startsWith("UNIQUE")
                        || s.startsWith("CHECK") || s.startsWith("FOREIGN KEY")) {
                    continue;
                }
                Matcher c = Pattern.compile("^\"([A-Za-z_][A-Za-z0-9_]*)\"\\s+\\S").matcher(s);
                if (c.find()) {
                    cols.add(c.group(1));
                }
            }
        }
        Matcher a = Pattern.compile(
                "(?i)ALTER\\s+TABLE\\s+\"([^\"]+)\"\\s+ADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?\"([^\"]+)\"")
                .matcher(sql);
        while (a.find()) {
            out.computeIfAbsent(a.group(1), k -> new TreeSet<>()).add(a.group(2));
        }
        return out;
    }

    static Map<String, Set<String>> parseCheckValues(String sql) {
        Map<String, Set<String>> out = new TreeMap<>();
        // 当前上下文表名：CHECK 约束写在 CREATE TABLE 里，本身不带表名
        Matcher t = Pattern.compile("(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?\"([^\"]+)\"\\s*\\((.*?)\\n\\s*\\);")
                .matcher(sql);
        while (t.find()) {
            String table = t.group(1);
            Matcher c = Pattern.compile("(?is)CHECK\\s*\\(\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"\\s+IN\\s*\\(([^)]*)\\)")
                    .matcher(t.group(2));
            while (c.find()) {
                Set<String> vals = out.computeIfAbsent(table + "." + c.group(1), k -> new TreeSet<>());
                Matcher v = Pattern.compile("'([^']*)'").matcher(c.group(2));
                while (v.find()) {
                    vals.add(v.group(1));
                }
            }
        }
        return out;
    }

    // ==================================================================== IO

    static Map<String, String> readTree(Path root, String suffix) {
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(suffix)).sorted().toList()) {
                out.put(root.relativize(f).toString(), normalize(read(f)));
            }
        } catch (IOException e) {
            throw new AssertionError("扫描 " + root + " 失败", e);
        }
        return out;
    }

    static String read(Path f) {
        try {
            return Files.readString(f);
        } catch (IOException e) {
            throw new AssertionError("读不了 " + f, e);
        }
    }
}
