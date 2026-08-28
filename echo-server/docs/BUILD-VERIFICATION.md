# echo-server 构建须知 —— 🔴 什么样的「通过」是假的

| | |
|---|---|
| 版本 | v1.3 · 2026-08-27（🆕 §7.2 **第五处假绿：测试名承诺后果、断言只查形态**，🔴 **它跑了也求值了、还会红，绿只因断言对象选错**，且它在守红线的反面；🆕 附节补**第二问**，因为前四处的统一判据对它不适用）<br>v1.2（🆕 §7 第四处假绿：空集合让 `allSatisfy` 恒过；§7.5 写进 `src/test` 的断言等于写进 TODO；附节把四处并成一条判据）<br>v1.1（§6 第三处假绿：常量闸门让 `&&` 右边永不求值；§4 探针改为常驻 `probe/`） |
| 管什么 | 🔴 **本工程哪些校验命令的「绿」不算通过**，以及唯一可信的那一条 |
| 谁必读 | 任何要在 `echo-server` 里改代码的人。**先读本文再跑第一条命令**，否则会浪费一小时去查一个不存在的问题（或者更糟：以为改对了） |

---

## 概述（读完这一节就够动手了）

- 🔴 **唯一可信的校验是全量 `mvn -o clean test-compile`。** 别的都可能绿着骗你。
- 🔴 **`mvn test` 在沙箱里禁用**：`surefire` 的 fork 会被打崩、构建中止，但输出长得像「用例很少但都过」。
- 🔴 **增量 `mvn compile` 的绿是假的**：`target/classes` 里的旧产物会顶替没重编的文件。本工程真的发生过——`EchoApi.java` 里两个同名同参方法（`javac: already defined`），`mvn compile` 照样绿。
- 🔴 **`-o` 是必须的**，不加会卡在等网。
- 🔴 **本工程未开 `-parameters`**：任何靠反射读参数名的断言恒过，等于什么都没验。
- 🔴 **恒假的常量闸门会让 `&&` 右边永不求值**，于是断言右边那一半行为的用例**无论它对不对都绿**（§6，实撞）。
- 🔴 🆕 **空集合让 `allSatisfy` / `allMatch` / `noneMatch` 恒过**（§7，本轮实撞 4 处，🔴 其中一条是**权限**断言）。
- 🔴 🆕 **写进 `src/test` 的断言现在等于写进 TODO**：`mvn test` 禁用 ⇒ 一条都不执行。要现在就守着的，写进 `probe/`（§7.5）。
- ⚠️ **沙箱里 `clean` 之后 protoc 拷不进去** —— 但有一个两行的绕法，见 §2，**不必为此跑到沙箱外**。
- 🔴 **纯逻辑断言别走 Maven**：`./probe/run.sh`（常驻探针，§4）。

**这些缺陷的共性：测试通过，但其实什么都没验。** 判断一条校验可不可信，问的不是「它绿不绿」，而是**「它失败的时候会不会绿」**。

---

## 一、三种假绿，逐条说清

### 1.1 `mvn test` —— 崩掉之后长得像「通过」

`surefire` 会 fork 一个 JVM 跑用例。沙箱环境下这个 fork 会被打崩，构建随之中止。

🔴 **要命的地方在于表现形式**：输出里既没有「fork 失败」这种刺眼的字样，也不是 `BUILD FAILURE`。你看到的是**一份用例数明显偏少、但全部通过的报告**——而「用例少」在一个正常演进的工程里根本不像故障。

**所以：不要用 `mvn test` 判断有没有改坏东西。** 需要跑断言时见 §4。

### 1.2 增量 `mvn compile` —— 拿旧产物当通过

Maven 的增量编译只重编改动过的源文件。于是当 `target/classes` 里躺着一份更早的、能用的 `.class` 时，**一个根本编不过的源文件可以长期不被发现**。

🔴 **本工程的实证**：`EchoApi.java` 里曾同时存在两个 `latestEchoText(PetProfile)`（同名同参，`javac` 必报 `already defined`）。也就是说 `echo-server` 从纳入版本控制起就编译不过，而本地 `mvn compile` 一直是绿的——因为那个文件"没改过"，从来没被重编。

**判据**：`clean` 之后能过，才叫能过。

### 1.3 未开 `-parameters` —— 反射读参数名的断言恒过

`pom.xml` 没有开 `<parameters>true</parameters>`，编译产物里不保留方法参数名（只有 `arg0`、`arg1`…）。

🔴 **后果**：任何形如「反射拿到参数名，断言它等于某个约定值」的测试，**永远通过**，因为它比较的两边都是它自己造出来的。这类断言看起来在守护一条契约，实际上一行都没验。

**要么开 `-parameters`，要么别写这类断言。** 保持现状的话，写之前先问一句：这条断言在契约被破坏时，会红吗？

---

## 二、唯一可信的校验命令（沙箱内也能跑）

```bash
cd Echo/echo-server
mvn -o clean test-compile
```

⚠️ **沙箱里它会在第一步就挂**，报：

```
Failed to execute goal ...protobuf-maven-plugin:0.6.1:compile ...
Unable to copy the file to .../target/protoc-plugins: Operation not permitted
```

原因：插件用的拷贝会连带复制文件属性，沙箱不允许；而 `clean` 已经把 `target/` 删了，protoc 必须重新拷进去。

🔴 **绕法是两行，先手工把 protoc 放进去，再跑不带 `clean` 的 `test-compile`**（`clean` 已经执行过，所以这仍然是一次**全量**编译，不是增量）：

```bash
cd Echo/echo-server
mvn -o clean                     # 让它删干净；protoc 那一步失败无妨
mkdir -p target/protoc-plugins
cp /Users/andy/.m2/repository/com/google/protobuf/protoc/4.35.0/protoc-4.35.0-osx-aarch_64.exe \
   target/protoc-plugins/        # 🔴 用 cp 不加 -p：不复制属性，沙箱才允许
chmod +x target/protoc-plugins/*.exe
mvn -o test-compile              # 全量：205 个主源 + 70 个测试源从零编译
```

**怎么确认它真的是全量**：输出里必须有

```
[INFO] Compiling 205 source files with javac ... to target/classes
[INFO] Compiling 70 source files with javac ... to target/test-classes
```

🔴 **如果看到的是 `Nothing to compile - all classes are up to date`，那这次校验什么都没验**，回到 §1.2。

> 📌 protoc 的版本号会随 `pom.xml` 里 `protobuf-java` 的版本变。拷之前先看一眼
> `ls ~/.m2/repository/com/google/protobuf/protoc/`，别照抄上面那个 `4.35.0`。

---

## 三、沙箱外跑（可选）

沙箱外没有属性拷贝的限制，直接一条命令：

```bash
cd Echo/echo-server && mvn -o clean test-compile
```

⚠️ macOS **没有 `timeout`**（那是 GNU coreutils 的）。想加超时用 `gtimeout`（需 `brew install coreutils`），或者干脆不加——本工程全量 test-compile 约 5 秒。

---

## 四、跑真断言：常驻探针 `probe/`，不走 Maven

纯逻辑的东西（归一化函数、可见性取交集、首句切分、排序比较器、上限并发）不需要 `surefire`。本工程把这类断言放在 🔴 **`probe/`（源码目录，随仓库走）**，一条命令跑：

```bash
cd Echo/echo-server
./probe/run.sh              # 全部
./probe/run.sh CardProbe    # 只跑一个（子串匹配）
```

⚠️ 前置：`target/classes` 得先存在（按 §2 编一次全量）。`run.sh` 会自己缓存依赖 classpath 到 `target/probe-cp.txt`。

现有探针：

| 探针 | 守什么 |
| --- | --- |
| `probe/com/echo/http/card/CardProbe.java` | 首句切分（含 emoji 不劈代理对）、可见性取交集、置顶排序 `NULLS LAST` |
| `probe/com/echo/http/store/StoreProbe.java` | 置顶上限与自动解除、可见性 CAS 与流水、**20 线程并发置顶** |
| `probe/com/echo/http/exposure/SurfaceProbe.java` | 网格层不记 `n`、全屏层记 `n` |
| `probe/com/echo/http/ranking/S4Probe.java` | `S4` 关闸、拒写、以及**开关那一半的行为**（见 §6） |

🔴 **这条路子逮到过真东西**：`SubjectFields.normalizeType(null)` 的 NPE（`List.of(...).contains(null)` 抛 NPE 而不是回 `false`）就是这么发现的——`mvn test` 那时候是绿的。

**为什么它可信**：断言失败进程非零退出，没有中间层能把失败吞成绿色。

> 📌 ⚠️ **`run.sh` 自己也验过一次会不会红**：往 `CardProbe` 里插一条 `check(..., false)`，
> 确认 `run.sh` 退出码为 `1`；还原后为 `0`。
> 🔴 **一个「永远返回 0 的测试脚本」正是本文档在讲的那类缺陷**，所以它不能只靠看起来对。
> （`run.sh` 里那句「grep 吃掉了退出码，单独再取一次」就是为这个 —— 管道的退出码来自 `grep`，
> 不是 `java`，少了那一行整个脚本会恒绿。）

---

## 五、另有一类假绿：拒收当成功（🔴 2026-08-27 新增）

这一条不是构建命令的问题，是**同一个毛病换了个地方长出来**，所以写在同一份文档里。

曝光上报 `POST /plaza/impressions` 的响应是 `{accepted, rejected}`。而在广场还发宠物窗口（`petId`）的那段时间里，`ExposureRecorder` 会因为 `NOT_CARD_FEED` **整批拒收**——这是对的（`petId` 写进 `t_card_exposure.cardId` 会污染权重衰减的数据源）。

🔄 🔴 **状态订正（2026-08-27，回代码核实）**：那段时间**已经过去**（`EchoApi.PLAZA_FEED_KIND` 已是 `KIND_CARD`），⚠️ 🔴 **但整批拒收<u>没有</u>随之消失** —— 拒收原因换成了 **`GRID_SURFACE`**：记 `n` 要求 `KIND_CARD && SURFACE_IMMERSIVE`，`/plaza` 登记的是 `SURFACE_GRID`，而全屏单卡层至今未实现（`EchoApi.IMMERSIVE_FEED_IMPLEMENTED = false`）。🔴 **所以本节这条假绿今天照样成立，判据一个字都不用改。**

🔴 **但对上层来说，一次「整批拒收」和一次「没有曝光发生」在响应里无法区分**，而 `t_card_exposure` 恒空 ⇒ `n` 恒为 0 ⇒ 权重衰减 / 制动表 / `SURGE` 三个模型全部空转，**且三个都不报错、日志里都是正常的**。

守它的东西是 `RankingReadiness.warnIfExposureSourceIsDead(...)` 那条启动告警，判据取**下发侧实际用的那两个常量**（与下发口径同源，不会出现「改了下发忘了改告警」）。

> 🔄 🔴 **判据已于 2026-08-27 从一条变成两条相与**：~~原文写「判据取 `EchoApi.PLAZA_FEED_KIND` 这<u>一个</u>常量」~~ —— **现在是 `PLAZA_FEED_KIND` 且 `IMMERSIVE_FEED_IMPLEMENTED`**。⚠️ 🔴 **只判前者会造出第四处假绿**：广场改发回忆卡那天告警自动消失、启动日志变干净，而 `n` 仍然恒为 0（理由已写进 `RankingReadiness` 类文档）。

**给写测试的人的判据**：断言「上报成功」时，**必须断 `accepted > 0`，不能只断 HTTP 200 或 `rejected == 0`**。整批拒收路径下 `accepted=0, rejected=N`，HTTP 仍是 200。

---

## 六、🔴 第三处假绿：恒假的常量闸门让 `&&` 右边永不求值（2026-08-27 实撞）

这一处是本轮**真撞上的**，不是推演。它和前两处的共性还是那一条：**测试通过，但其实什么都没验。**

### 6.1 长什么样

`S4DrainPolicy.enabled()` 原先是：

```java
public boolean enabled() {
    // InteractionScopeMigration.isCardLevel() 恒为 false
    return InteractionScopeMigration.isCardLevel() && switches.isEnabled(KEY_S4_NATURAL_DRAIN);
}
```

左边那个判据**恒假**（它比的是两个常量：`CARD.equals(WINDOW)`）。于是：

- 整个表达式恒假 ⇒ `enabled()` 永远回 `false`；
- 🔴 **`&&` 短路，右边那次 `switches.isEnabled(...)` 从来没有被求值过。**

后果是单测里所有形如

```java
assertThat(policy.enabled()).isFalse();
```

的断言 🔴 **无论开关逻辑对不对都会通过**。哪怕「拒写」整个失效、哪怕 `setEnabled(false)` 反而把开关打开了，那些断言照样绿——它们**从来没碰到过开关那半边代码**。

### 6.2 为什么它比前两处更难发现

前两处至少有个外部迹象（用例数偏少、参数名是 `arg0`）。这一处 🔴 **没有任何迹象**：

- 用例数正常、全绿；
- 代码读起来完全合理（「迁移完成 + 开关打开才生效」）；
- 覆盖率工具也会把 `enabled()` 这一行算成**已覆盖**——它确实被执行了，只是右半边没有。

⚠️ **它甚至伪装成一条"暂时"的状态**：注释写着「迁移第③步落地后自己变可开」，读的人于是去等一个不会到来的迁移（那一步已被 `DECISIONS RK-H` 取消），而不是去补那条真正缺的裁定。

### 6.3 怎么改的

把闸门从常量表达式改成**实例字段** + 一个**包内可见、仅供单测**的构造器：

```java
public static final boolean RULED_OPEN = false;          // 生产：关闸，理由写在字段上
public S4DrainPolicy(FeatureSwitchService s) { this(s, RULED_OPEN); }
S4DrainPolicy(FeatureSwitchService s, boolean ruledOpen)  // 🔴 包内可见，不对外
```

于是单测可以构造一个「裁定已开」的实例，**真正走到开关那一半**去断言它的行为。生产路径仍读 `RULED_OPEN`，安全性与改之前一致。

### 6.4 给写测试的人的判据

🔴 **写完一条断言，问自己：被断言的那段代码，真的被执行到了吗？**

具体到 `&&` / `||`：

- 表达式里有**恒定判据**（常量、`static final`、比较两个常量的方法）时，另一半可能从未求值；
- 断言「整体为 `false`」是**最弱**的断言——它对每一个子条件都成立，所以**不区分**是哪一个让它为 `false` 的；
- 想验某一半，就得让另一半为**真**，必要时留一个测试专用的注入口（像 6.3 那样），🔴 **而不是把生产开关改成可配**。

---

## 七、🔴 第四处假绿：空集合让 `allSatisfy` 恒过（2026-08-27 实撞）

### 7.1 长什么样

`ReactionArrivalsTest` 里有四条这样的断言，**一条非空保护都没有**：

```java
// arrivalsStartUnread / readingMergedArrivalClearsWholeCard
// onlyTheOwnerCanSweepACard / ordinaryMessageIdsAndArrivalIdsCoexistInOneRequest
assertThat(arrivals(owner)).allSatisfy(it -> assertThat(it.get("read")).isEqualTo(false));
```

🔴 **`arrivals(owner)` 返回空列表时，这四条全部通过。** AssertJ 的 `allSatisfy`
（以及 `allMatch` / `noneMatch` / `doesNotContain`）在空集合上**恒真**——
「所有元素都满足」对一个没有元素的集合是平凡成立的。

### 7.2 为什么这不是纸上的担心

`/messages/arrivals` 的取数路径上有一处**静默跳过**：

```java
if (pet == null) {
    continue;   // 窗已不在：整卡略过
}
```

窗查不到就 `continue`，不报错、不记日志。所以任何让 `petById` 失效的回归
（id 口径变了、库里没这行、拉黑判定顺序被调换）都会让 `arrivals` **整体变空**，
而这四条用例继续绿。

🔴 **四条里最坏的是 `onlyTheOwnerCanSweepACard`** —— 它断言的是
「陌生人散不掉别人的到达」，**一条权限断言**。空列表时它宣称权限正确，
而实际上一次都没检查过。⚠️ **权限断言恒过，是这一类里后果最重的形态。**

### 7.3 与第三处（§6）是同一个形状

对照两句话：

| | 断言 | 为什么弱 |
|---|---|---|
| §6 | `a && b` 整体为 `false` | 🔴 它对**每一个**子条件都成立，所以不区分是哪一个让它为 `false` |
| §7 | 集合**每个元素**都满足 `P` | 🔴 它对**每一个** `P` 都成立（当集合为空），所以不区分 `P` 对不对 |

**共性：断言在「什么都没有」的情况下也成立，于是它不区分「全对」与「什么都没发生」。**
§6 是短路求值让半个条件不执行，§7 是空集合让谓词一次都不执行——
🔴 **两者都属于「被断言的那段逻辑根本没跑」**，正是 §6.4 那条判据要问的东西。

### 7.4 怎么改的

四处都加了非空保护，并在 `as()` 里写明理由：

```java
assertThat(arrivals(owner))
        .as("🔴 空列表会让 allSatisfy 恒过 —— 那时这条用例什么都没验")
        .isNotEmpty()
        .allSatisfy(it -> assertThat(it.get("read")).isEqualTo(false));
```

📌 **对照：`ModerationApiTest.everyActionWritesBothLogs` 与
`ReactionArrivalsTest` 第 127 行的 `allSatisfy` 前面都有 `hasSize(n)`，
所以那几处不受影响。**这说明本仓已经有人知道要这么写——
🔴 **靠人记的东西，记住的那几处和忘掉的那几处会同时存在，而且看起来一样。**

### 7.5 ⚠️ 还有一层：这四条修完也不会跑

🔴 **`mvn test` 在本工程禁用（§1.1），所以 `src/test` 下的断言一条都不执行。**
修好它们的意义是「等 `surefire` 能跑时它们是对的」，**不是「现在它们在守着」**。

同一个道理已经在 `EchoFallbackCopyTest` 上付过一次代价：那个文件为兜底文案池写了
四条很好的断言（含一条防止扫描目录失效的自我保护），**而它一次都没跑过**。
⚠️ 这比没有测试更坏——它看起来有机器检查守着，于是下一个改文案的人以为踩线会被拦。
🔴 **一个不运行的断言和一个恒真的断言，效果完全一样。**
所以那几条已经移进 `probe/CopyProbe`（会真的执行），并顺手查出
`CopyGuardFilter` 的替代文案自己踩 `CR2`/`DP2`。

📌 **判据：要「现在就守着」的断言，写进 `probe/`；写进 `src/test` 的等于写进 TODO。**

---

## 七之二、🔴 第五处假绿：测试名承诺后果，断言只查形态（2026-08-27 实撞）

> 由前端视觉线在 `D4`/`D5` 红线排查中撞见并回代码核实。**冻结期间不修**（`C-8`/`C-10` 在冻结中，
> 见 `docs/API-CONTRACT.md` §18）；本节只登记形态与后果。

🔴 **它与前四处不同：这一次代码跑了，断言也求值了，而且它真的会红。它绿，是因为断言的对象选错了。**

```java
// echo-server/src/test/java/com/echo/http/EchoApiTest.java · rememberWallHasNoExactCount()
Map<String, Object> wall = invoke("GET", "/windows/" + windowId + "/remember", visitor, null);
assertThat(wall).containsKeys("warmthLevel", "faces", "meRemembered");
assertThat(wall.get("meRemembered")).isEqualTo(true);
// 红线：不返回精确总数/排名字段
assertThat(wall).doesNotContainKeys("count", "total", "rememberCount", "rank");
```

**测试名承诺的是后果**（`NoExactCount` —— 拿不到精确人数）。**断言检查的是形态**（没有叫这四个名字的字段）。

而实际的泄漏面一个都不在这四个名字里：`warmthLevel` 是 `min(1, faces/20)`，
`faces = round(warmthLevel × 20)` **一步反解、精确到人**，算式就在 `EchoApi.java` 里；
`faces[]` 的长度本身也是那个数。**两者都在场，测试照样绿。**

### 🔴 更坏的一层：它在守这条红线的反面

倒数第三行 `containsKeys("warmthLevel", "faces", ...)` —— **它把泄漏面断言成了必须存在的契约。**

后果是具体的：解冻后要动 `C-10`（`warmthLevel` 改发档位枚举）或 `C-8`（对某些视角不发 `faces`），
**这条名叫「无精确计数」的红线测试会第一个变红**，把修复挡在门外。

> 🔴 **它不只是没在守这条红线，它在守这条红线的反面。**

⚠️ 给解冻后动手的人：**看到这条测试变红，先确认不是自己改坏了** —— 它本来就该红。

### 判据

📌 **测试名里承诺了「后果」（`NoXxx` / `红线` / `Only` / `Never`）时，检查一遍：
断言查的是那个后果，还是只是几个字段名在不在？**

字段名可以改、可以换一个不在黑名单里的名字、也可以用一个能反解回去的量绕过去——
**黑名单式断言挡住的是命名，不是能力。**

### ⚠️ 这一处不适用本文附节的统一判据

附节问的是「被断言的那段逻辑，这一次真的跑了吗」。**第五处的答案是「跑了」**，
所以那句「一条从来没红过的断言，与一条恒真的断言不可区分」在这里**不成立**——
它是一条**活着的、会红的**断言，只是守错了东西。见附节新增的第二问。

---

## 八、一句话checklist

改完代码，交出去之前：

1. `mvn -o clean` → 手工放 protoc → `mvn -o test-compile`（§2），**并确认输出里有 `Compiling N source files`**。
2. 有纯逻辑改动 → 往 `probe/` 里补断言，跑 `./probe/run.sh`（§4）。
3. 🔴 **不要用 `mvn test` 的结果作为交付依据**（§1.1）；**要现在就生效的断言写进 `probe/`**（§7.5）。
4. 🔴 **不要写靠反射读参数名的断言**（§1.3）。
5. 断言「某个东西被接受了」时，检查一遍：**它被整批拒收的时候，这条断言会红吗**（§5）。
6. 🔴 断言里有 `&&` / `||` 时，检查一遍：**有没有哪一半从来没被求值过**（§6）。
7. 🔴 用 `allSatisfy` / `allMatch` / `noneMatch` / `isEmpty` 时，**先断非空**（§7）。
8. 🔴 测试名承诺了后果（`NoXxx` / `红线` / `Only` / `Never`）时，检查一遍：**断言查的是那个后果，还是只是几个字段名在不在**（§7.2）。

---

## 🔴 附：四处假绿的统一判据

四处的成因各不相同（执行器崩了 / 旧产物 / 反射拿不到名字 / 短路 / 空集合），
但**问出来的问题只有一个**：

> 🔴 **被断言的那段逻辑，这一次真的跑了吗？**

- `mvn test` 崩掉 → 一条都没跑；
- 增量编译 → 改的那个文件没重编；
- 未开 `-parameters` → 反射拿到 `arg0`，比较的是两个占位符；
- 短路求值 → `&&` 右边没求值；
- 空集合 → 谓词没求值。

⚠️ **所以「绿」本身不携带信息，携带信息的是「它红过」。**
🔴 **一条从来没红过的断言，与一条恒真的断言，在证据上不可区分。**

### 🔴 第二问（2026-08-27 补，因为第五处假绿不适用上面这一问）

§7.2 那处**跑了、也求值了、也确实会红**——它绿只是因为断言的对象选错了。
所以上面那一问答「跑了」并不能结案，还要再问一句：

> 🔴 **这条断言查的，是它名字承诺的那个后果，还是只是一组字段名？**

两问合起来才完整：**第一问查「它有没有执行」，第二问查「它执行的是不是该查的东西」。**
⚠️ 前四处败在第一问，第五处败在第二问，**而第五处从日志和覆盖率上完全看不出来**——
它有执行、有覆盖、名字还写着红线。
