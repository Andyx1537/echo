# echo-server

回响 (Echo) 后端业务工程 —— **BE-1 ~ BE-5**。本工程是一个独立的 Maven 项目，把
[Aengine](../aengine) 游戏引擎模块作为**底层引擎支援**，复用其
network(Netty/WebSocket)、persistence、event、scheduler、template、cache、util 能力。
根目录 Maven reactor 同时构建 Aengine 与 echo-server，业务仓储统一基于引擎的
`CachedJDBCRepository`，避免源码与本地已发布构件漂移。

- 坐标：`com.echo:echo-server:0.1.0-SNAPSHOT`，packaging=jar
- JDK 26，UTF-8
- 业务包根：`com.echo.*`（独立于引擎 `com.aengine.*`）

## 目录结构

```
com.echo
├── bootstrap   启动类（EchoServer）、DB 初始化（EchoDatabase）、过期回声清理任务（EchoExpiryJob）
├── gateway     WebSocket 接入/会话 + 协议 Handler（Login/MindProfile/Space/Resonance/Echo/Heartbeat）
├── module      业务模块：mind / space / resonance / echo / social / account / avatar
│               （各含 实体 + Aengine CachedJDBCRepository 仓储 + Service 领域逻辑）
└── infra
    ├── llm      LLM 补全抽象 ILlmClient + 假实现 MockLlmClient
    ├── vector   向量库抽象 IVectorStore + 内存假实现 InMemoryVectorStore + pgvector 真实现 PgVectorStore
    └── persistence  Aengine DB 的 PG 兼容适配（供 HTTP Store/pgvector 过渡使用）
```

## 协议（消息号段与请求→响应）

JSON 兼容、消息类名 `_<id>` 后缀、`int64` 序列化为字符串（§3.0）。除登录/心跳外的业务消息
**不进 `noNeedCheckMessage` 白名单**，依赖会话身份（引擎在 `PacketHandlerManager.forward`
校验 `session.getIdenty() != null`）。

| 模块 | 请求 → 响应 | 关键字段 |
|---|---|---|
| 账号 | `LoginReq_1001` → `LoginResp_1002` | openId → code/accountId/newAccount |
| 意识档案 §4.1 | `SubmitPrefsReq_1201` → `MindProfileResp_1202` | rawPrefs[] → code/profileId/vectorId/enrichedPrefs |
| 意识空间 §4.2 | `EnterSpaceReq_1301` → `SpaceSnapshotResp_1302` | (空) → spaceId/presetSetId/dynamicParams/hostConfig |
| 意识空间 §4.2 | `UpdateHostConfigReq_1303` → `UpdateHostConfigResp_1304` | broadcast/asyncOnly/resonanceThreshold/allowBattle → hostConfig |
| 共鸣 §4.3 | `QueryResonanceReq_1401` → `ResonanceListResp_1402` | topN/threshold → candidates[accountId,score] |
| 回声 §4.3 | `PullEchoesReq_1501` → `EchoListResp_1502` | ownerSpaceId → echoes[echoId,fromAccountId,payload,expireAt] |
| 回声 §4.3 | `LeaveTraceReq_1503` → `LeaveTraceResp_1504` | ownerSpaceId/payload/ttlMillis → echoId/expireAt |
| 系统/心跳 §3.1 | `Heartbeat_9001` → `HeartbeatAck_9002` | clientTime → serverTime |

> 共鸣 `score` = pgvector 余弦距离算子 `<=>` 的结果（越小越相似，约 [0,2]）；`threshold` 为距离上限。

## 向量库（pgvector，BE-4）

`IVectorStore` 两实现，按 DB 开关注入服务层：
- **DB 关（默认）/单测/本地**：`InMemoryVectorStore`（内存余弦距离）。
- **DB 开**：`PgVectorStore`，基于 `PgDb` + pgvector，读写 `t_self_vector.embedding vector(768)`。

维度常量集中在 `IVectorStore.DIM = 768`（与 `schema.sql` 一致）。`encode` 为确定性占位哈希
（接口默认方法，两实现共享），待真实嵌入模型替换。启动配置若指定非 768 维会直接失败；
每行同时记录 provider/model/version/embeddedAt，不同模型身份的向量不会混合检索。列归属：关系元数据由 `SelfVectorRepository`
通用 CRUD 维护（含建行），`embedding` 列仅由 `PgVectorStore` 维护。实际 SQL：

```sql
-- upsert（行由仓储先建好，向量通道只写 embedding 列）
UPDATE "t_self_vector" SET "embedding" = ?::vector WHERE "accountId" = ?;

-- topN（余弦距离升序，过滤距离 > threshold，取前 k）
SELECT "accountId", ("embedding" <=> ?::vector) AS score
FROM "t_self_vector"
WHERE "embedding" IS NOT NULL
  AND "embedProvider" = ? AND "embedModel" = ? AND "embedVersion" = ?
  AND ("embedding" <=> ?::vector) <= ?
ORDER BY score ASC LIMIT ?;
```

近邻检索使用 `vector_cosine_ops` HNSW 索引。模型切换后由 `VectorRebuildService` 按账号批次显式回填，
不会在启动阶段扫描全表或自动产生供应商调用费用。模型版本通过 `ECHO_EMBED_VERSION` 配置。

## 心跳与连接 idle 超时（§3.1，BE 结论）

- Aengine `WebSocketServer` 连接 **idle 超时默认 30s**（`idle=30`，握手完成时 `conn.setIdle(idle*1000)`，
  `connection-idle-checker` 线程每秒扫描，超时即关闭）。任意上行包都会在 `EventDispatcher.onReceive`
  刷新读时间、重置 idle。
- **结论**：客户端心跳间隔建议 **15s**（§3.1 默认），服务端 idle 超时建议 **≥40s**。本工程已在
  `EchoServer` 于 `bind` 前 `server.setOpt("IDLE_SEC", 40)` 配置为 **40s**（≈2.7× 心跳间隔，留弱网余量）。
- `Heartbeat_9001` 放入 `noNeedCheckMessage` 白名单：心跳是连接级保活、非业务操作，登录前后均可发送、
  不依赖身份，逻辑统一且最轻量。

## 过期回声清理（§4.3）

`Scheduler` + `CronTrigger`（6 段制，默认 `0 * * * * *` 每分钟）驱动 `EchoExpiryJob`，调用
`EchoService.purgeExpired()` 执行 `DELETE FROM "t_echo" WHERE "expireAt" < now`。仅 DB 开启时调度。

## 构建前置：先安装 Aengine 到本地仓库

本工程依赖 `com.aengine:Aengine:1.0-RELEASE`，需先在引擎工程目录把它装进本地 Maven 仓库：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null \
  || echo /opt/homebrew/opt/openjdk@26/libexec/openjdk.jdk/Contents/Home)
cd ../../Aengine
mvn -q -DskipTests install
```

## 构建本工程

🔴 **先读 [`docs/BUILD-VERIFICATION.md`](docs/BUILD-VERIFICATION.md)** —— 本工程有**五处**「假绿」，
其中 `mvn compile` 和 `mvn test` **都会在编译不过 / 用例没跑的情况下报成功**。下面这条是唯一可信的校验：

⚠️ 第五处（`§7.2`）和前四处不是一类：**它跑了、也求值了、也会红** ——
它绿是因为**断言查的是字段名，不是测试名承诺的那个后果**。🔴 **这一处从日志和覆盖率上完全看不出来。**

```bash
cd ../Echo/echo-server   # 即本目录
mvn -o clean                     # protoc 那一步在沙箱里会失败，无妨
mkdir -p target/protoc-plugins
cp ~/.m2/repository/com/google/protobuf/protoc/4.35.0/protoc-4.35.0-osx-aarch_64.exe target/protoc-plugins/
chmod +x target/protoc-plugins/*.exe
mvn -o test-compile              # 必须看到 "Compiling 2xx source files"，否则这次校验什么都没验
```

沙箱外可直接 `mvn -o clean test-compile` 一条搞定。⚠️ **不要用 `mvn compile` 判断改没改坏**——
增量编译会拿 `target/classes` 里的旧产物当通过，本工程真的靠这个藏过一个同名同参方法。

## 运行 EchoServer

启动一个最小可用的 WebSocket 服务（默认端口 9001）：

```bash
# 端口可用首个程序参数或系统属性 echo.port 配置；绑定地址用 echo.host（默认 0.0.0.0）
mvn -q -DskipTests exec:java -Dexec.mainClass=com.echo.bootstrap.EchoServer -Dexec.args="9001"
# 或打包后：java -Decho.port=9001 -cp <classpath> com.echo.bootstrap.EchoServer
```

> 默认无 DB：仅心跳 Handler 注册，WebSocket 就绪。设 `-Decho.db.enabled=true -Decho.db.config=<path>`
> 启用登录 + 意识档案/空间/共鸣/回声业务闭环。标准配置使用 `db.schemaMode=validate`：
> 启动时校验 `t_schema_version` 以及 Repository 所需表/列，任何不一致直接拒绝启动，不自动改库。

## 测试

🔴 **`mvn test` 在沙箱里禁用，它的「绿」不算通过。** `surefire` 的 fork 会被打崩、构建随之中止，
而输出长得像**「用例很少但都过」**——「用例少」在一个演进中的工程里根本不像故障，所以这个假绿很能骗人。

纯逻辑断言改用**常驻探针**，不走 Maven：

```bash
./probe/run.sh              # 全部（需先按上面编一次全量）
./probe/run.sh CardProbe    # 只跑一个
```

探针带 `main`、失败即非零退出，中间没有任何层能把失败吞成绿色。
🔴 那条路子逮到过一个真 NPE。守什么、有哪些探针见
[`docs/BUILD-VERIFICATION.md` §4](docs/BUILD-VERIFICATION.md)。

⚠️ 另外两条**本工程特有的假绿**，写断言前必看：

- **未开 `-parameters`**：任何靠反射读参数名的断言恒过，等于一行都没验（§1.3）。
- 🔴 **恒假的常量闸门让 `&&` 右边永不求值**：断言「整体为 `false`」时，
  另一半可能从来没被执行过，**改坏了也不会红**（§6，本工程实撞过一次）。
