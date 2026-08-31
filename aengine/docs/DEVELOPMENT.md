# Aengine 开发文档

> 本文面向在 Aengine 上做二次开发的工程师，介绍工程结构、各模块支持能力与使用方式、测试与协作规范。
> 架构理念见 [`ARCHITECTURE.md`](./ARCHITECTURE.md)，从 `game-engine` 的升级/破坏性变更对照见 [`MIGRATION.md`](./MIGRATION.md)。

---

## 1. 项目简介

Aengine 是从 `game-engine` 平移并升级而来的游戏服务端基础引擎，统一封装了**持久化（repo）、网络（network）、类表元数据（class table）、调度（scheduler）、配置表（template）、事件（event）、缓存（cache）、通用工具（util）** 等支持能力。

### 技术栈

| 领域 | 选型 |
|---|---|
| 语言 / 运行时 | Java 26（OpenJDK 26） |
| 构建 | Maven，`com.aengine:Aengine:1.0-RELEASE` |
| 网络 | Netty `4.2.1.Final`（当前最高稳定线 4.2.x） |
| 序列化 | protobuf `4.35.0` + protobuf-java-util、Gson |
| 持久化 | MySQL（mysql-connector-j `9.3.0`）+ HikariCP `6.3.0` |
| 缓存/分布式 | Jedis `6.0.0`、concurrentlinkedhashmap-lru |
| 配置表 | Apache POI `5.5.1`（Excel） |
| 配置读取 | snakeyaml `2.4`、commons-io |
| 日志 | SLF4J `2.0.17` + Logback `1.5.18` |
| 样板代码 | Lombok `1.18.38` |
| 测试 | JUnit 5 `5.12.2` + AssertJ `3.27.3` + Mockito `5.18.0` |

---

## 2. 环境与构建

### 环境要求
- JDK 26（`JAVA_HOME` 指向 26）
- Maven 3.9+

```bash
# macOS Homebrew 示例
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

### 常用命令

```bash
mvn clean compile     # 编译
mvn test              # 运行全部单元测试（当前 51 个用例）
mvn clean package     # 打包 jar
```

---

## 3. 工程结构

包根：`com.aengine`（共 164 个源文件）。括号内为该目录直接包含的 `.java` 数量。

```
com/aengine
├── cache (6)                     缓存：ICache / SimpleCache / LRUCache / Element / CacheManager / ICacheListener
├── event (4)                     事件总线：IEvent / EventBusImpl / EventReceiver / @EventHandleMethod
├── network
│   ├── netty (14)                网络抽象：IConnection / ISocketServer / Packet / EventDispatcher / 校验和(CRC16/CRC32/MD5)
│   │   ├── tcp (6)               TCP 实现：TcpServer / TcpClient / Encoder / Decoder / ServerHandler / NettyConnection
│   │   ├── udp (5)               UDP 实现：UdpServer / Encoder / Decoder / ServerHandler / OutboundHandler
│   │   └── websocket (8)         WebSocket 实现：WebSocketServer / Encoder / Decoder / ServerHandler
│   └── support (17)              会话与分发：PacketHandlerManager / @HandlerMethod / @HttpHandlerMethod /
│                                 PlayerSession / ClientSession / TransientSession 及各自 Manager
├── persistence (11)              repo 核心：IRepository / AbstractEntity / TableMeta / ColumnMeta / IndexMeta /
│   │                             CacheMeta / CacheIndex / CachedRepository / DelaySaveRepository / MetaException
│   ├── annotation (8)            类表注解：@Table @Column @Pk @Index @Cache @Fk @MappedSuperclass @CRepository
│   ├── db (6)                    MySQL：DB / DBManager / JDBCRepository / CachedJDBCRepository /
│   │                             DelayedJDBCRepository / RollingJDBCRepository
│   ├── redis (4)                 Redis：Redis（Jedis 封装）/ RedisRepository 等
│   └── cache (1)                 持久化层缓存接口
├── scheduler (6)                 调度：Scheduler / Trigger / CronTrigger / CronSequenceGenerator / TriggerTask / TaskContext
├── template (18)                 配置表：ExcelReader / Template / TemplateMeta / ColumnMeta / TypeEnum / Rule /
│                                 GeneratorClassUtil / ExportTemplate / 各类校验注解(PK/FK/UNIQUE/ENUM/RV...)
└── util (13)                     通用工具
    ├── clazz (1)                 ClassUtil（包/JAR 类扫描）
    ├── collection (3)            Pair / ConcurrentHashSet / LinkedList
    ├── concurrent (3)            WrappedRunnable / 拒绝策略 / 方法调用统计
    ├── id (3)                    IDGenerator（雪花）/ UUIDGenerator / UUIDGenerateSupport
    ├── lock (8)                  ObjectLock / ReferenceCountedLockManager / ChainLock / LockUtil
    │   └── distributedLock (2)   DistributedLock / RedisLockSupport（分布式锁）
    ├── pubsub (9)                Redis 发布订阅 / 跨服消息
    ├── thread (4)                OrderedExecutorService / ActorExecutorService / NamedThreadFactory
    ├── xmlReader (2) / ymlReader (1)   XML / YAML 配置读取
    └── (DateUtil / StringUtils / GsonUtil / NetworkUtil / ProtobufUtil / NetConfig / RedisConfig / ServerConfig ...)
```

---

## 4. 各模块支持能力与用法

### 4.1 persistence（持久化 / 类表元数据）

**核心思路**：实体类用注解描述表结构，`TableMeta.parse()` 通过反射解析为元数据，Repository 基于元数据完成增删改查与缓存。

**定义实体**：实现 `AbstractEntity`，用注解描述列/主键/索引/缓存。

```java
@Table(name = "t_player",
       index = { @Index(name = "idx_name", columns = {"name"}) },
       cache = { @Cache(columns = {"name"}) })   // 被缓存的列必须 readOnly
public class Player implements AbstractEntity {
    @Pk
    @Column(name = "id")
    private long id;

    @Column(name = "name", length = 64, readOnly = true)
    private String name;

    @Column(name = "level")
    private int level;
    // 省略 getter/setter（可用 Lombok）
}
```

**Repository 体系**：

| 类 | 用途 |
|---|---|
| `IRepository<T>` | 仓储接口（add/remove/get/list/save/truncate） |
| `JDBCRepository<T>` | MySQL 仓储（抽象，按实体类型继承使用） |
| `CachedJDBCRepository<T>` | 带二级缓存的 MySQL 仓储 |
| `RedisRepository<T>` | Redis 仓储 |
| `CachedRepository<T>` | 实体缓存 + 索引缓存（被上面组合使用） |
| `DelaySaveRepository` / `DelayedJDBCRepository` | 延迟落库 |

**初始化数据源**（`DBManager` 单例）：

```java
DBManager mgr = DBManager.getInstance();
mgr.add(dbProperties);                 // 注册 MySQL（HikariCP，兼容 dbcp2 风格属性键）
mgr.addRedis(redisConfigList);         // 注册 Redis
Redis redis = mgr.getRedisCache();     // 默认 Redis
```

> 自定义仓储：`class PlayerRepo extends CachedJDBCRepository<Player> { ... }`，泛型参数即实体类型。

### 4.2 network（网络）

**抽象层** `network/netty`：`ISocketServer` / `IConnection` / `Packet`（统一包头：TCP/UDP 标志、是否需要 ACK、协议类型 protobuf/json）/ `EventDispatcher` / 校验和 `ICheckSum`（CRC16/CRC32/MD5）。

**三种传输实现**：`tcp.TcpServer`、`udp.UdpServer`、`websocket.WebSocketServer`，统一 `bind(ip, port)` + `start()`。

```java
TcpServer server = new TcpServer(/* threads */ 4);
// 或带收发校验和：new TcpServer(upCheckSum, downCheckSum, threads)
server.bind("0.0.0.0", 9000);
server.start();
```

**消息处理**：用 `@HandlerMethod` 注解方法，注册到 `PacketHandlerManager`。

```java
public class LoginHandler {
    @HandlerMethod(/* value=true 表示涉及 Redis 改动、需分布式锁 */)
    public void onLogin(IoSession session, LoginReq_1001 req) { ... }
}

PacketHandlerManager manager = new PacketHandlerManager(redisLockSupport);
manager.register(new LoginHandler());
```

> 消息号约定：消息类名以 `_<id>` 结尾，`NetworkUtil.getMessageID(clazz)` 取其后缀数字。
> HTTP 模拟见 `@HttpHandlerMethod` + `HttpServerSimlation`。

### 4.3 event（事件总线）

注解驱动的事件分发，支持同步 / 异步 / 分布式锁三种模式。

```java
EventBusImpl.init(redisLockSupport, /* 异步线程数 */ 2);

public class PlayerListener {
    @EventHandleMethod                      // 同步
    public void onLevelUp(LevelUpEvent e) { ... }

    @EventHandleMethod(async = true)        // 异步（走线程池）
    public void onAsync(SomeEvent e) { ... }

    @EventHandleMethod(value = true)        // 分布式：按 event.getIdenty() 加 Redis 锁
    public void onDistributed(CrossEvent e) { ... }
}

EventBusImpl.getInstance().regist(new PlayerListener());
EventBusImpl.getInstance().post(new LevelUpEvent(...));
```

### 4.4 scheduler（调度）

基于 `ScheduledThreadPoolExecutor`，支持延时、固定频率、Cron 表达式（6 段：秒 分 时 日 月 周）。

```java
Scheduler scheduler = new Scheduler("game-scheduler", 2);
scheduler.scheduleWithDelay(task, 5, TimeUnit.SECONDS);
scheduler.scheduleAtFixedRate(task, 1, TimeUnit.MINUTES);
scheduler.schedule(new TriggerTask(task, new CronTrigger("0 0 0 * * *"))); // 每日 0 点
```

### 4.5 cache（本地缓存）

```java
ICache<String, Player> cache = new SimpleCache<>(/*timeToIdle秒*/ 0, /*timeToLive秒*/ 300);
ICache<Long, Item>     lru   = new LRUCache<>(/*maxElements*/ 10000, 0, 600, evictionListener);
// CacheManager 单例统一登记并定时清理过期项
```

### 4.6 template（配置表）

`ExcelReader` 按固定表头读取 `#` 开头的 sheet（行序：1 表名 / 2 规则 / 3 描述 / 4 标记 / 5 字段名 / 6 字段类型，第 7 行起为数据），`TypeEnum` 支持 `int/long/bool/double/string/json/date/list<>/map<,>/table/byte`。`GeneratorClassUtil` 可由配置表生成 Java 实体代码。

```java
Map<String, Template> templates =
        new ExcelReader().parse(file, StandardCharsets.UTF_8, ColumnMeta.FLAG_SERVER);
```

### 4.7 util（通用工具）

| 子包/类 | 能力 |
|---|---|
| `id.IDGenerator` | 雪花算法（workerId 0~1023，时间有序、线程安全） |
| `id.UUIDGenerator` | 基于 `UUIDGenerateSupport`（Redis）生成全局 ID |
| `lock` | `ObjectLock`（可重入对象锁）/ `ReferenceCountedLockManager`（按 key 引用计数锁）/ `ChainLock` |
| `lock.distributedLock` | `DistributedLock` + `RedisLockSupport`（Redis 分布式锁） |
| `pubsub` | Redis 发布订阅、跨服消息（`RedisActiveMessenger` 等） |
| `thread` | `OrderedExecutorService`（按 key 顺序执行）/ `NamedThreadFactory` |
| `collection` | `Pair` / `ConcurrentHashSet` |
| `clazz.ClassUtil` | 扫描包/JAR 中的类（组件动态注册） |
| `ProtobufUtil` | protobuf ↔ JSON/Text（封装 protobuf-java-util） |
| `DateUtil/StringUtils/GsonUtil/NetworkUtil` | 日期、字符串、JSON、网络工具 |
| `NetConfig/RedisConfig/ServerConfig` | 配置 POJO（Lombok `@Getter/@Setter`） |
| `xmlReader/ymlReader/PropertiesLoader` | XML / YAML / Properties 配置读取 |

---

## 5. 测试体系

- 框架：JUnit 5 + AssertJ + Mockito。
- 现状：**51 个用例覆盖全部支持模块**，`mvn test` 全绿。
- 策略：纯逻辑模块写真实断言；依赖 Redis/MySQL/Netty 的边界用 Mockito mock（如 `RedisLockSupport`、`UUIDGenerateSupport`），只验证调用逻辑。

| 测试类 | 覆盖模块 |
|---|---|
| `cache.CacheTest` | SimpleCache / LRUCache / Element |
| `event.EventBusTest` | 同步 / 异步 / 分布式事件（mock Redis 锁） |
| `scheduler.SchedulerTest` | Cron 解析 / CronTrigger / 延时任务 |
| `template.TemplateTest` | TypeEnum / ExcelReader（POI 生成 xlsx 回读） |
| `persistence.PersistenceMetaTest` | TableMeta/ColumnMeta 解析 / CachedRepository / CacheIndex |
| `network.netty.NetworkTest` | CRC16/CRC32/MD5 / Packet |
| `util.UtilTest` | StringUtils / GsonUtil / DateUtil / NetworkUtil |
| `util.id.IdGeneratorTest` | 雪花 ID / UUIDGenerator（mock） |
| `util.collection.CollectionTest` | Pair / ConcurrentHashSet |
| `util.lock.LockTest` | ObjectLock / ReferenceCountedLockManager（并发） |
| `util.lock.distributedLock.DistributedLockTest` | 分布式锁（mock Redis） |

**新增测试约定**：测试类放在 `src/test/java` 下与被测类相同包（便于访问包级可见成员）；外部服务一律 mock，不在单测中连真实中间件。

---

## 6. 编码与协作规范

- **工作流**：遵循"**理解 → 确认 → 执行**"。收到指令先复述理解，有歧义/破坏性操作先与提出者确认再动手（见 `.cursor/rules/aengine-workflow.mdc`）。
- **依赖策略**：统一采用当前**最新稳定版**（排除 alpha/beta/RC/M 预发布）；升级破坏性 API 时在 `MIGRATION.md` 记录旧→新对照。
- **代码头信息**：源码中**不保留** `@author` / `创建时间` 等个人元信息（已全量清理），统一以 Git 历史追溯作者与时间。
- **日志**：使用 SLF4J `Logger`，禁止 `commons-logging` 与单参 `error(Throwable)` 写法。
- **样板代码**：POJO/配置类优先用 Lombok；作 Map key 的元数据类（如 `ColumnMeta`/`CacheMeta`）不要用 `@Data`，避免改写 `equals/hashCode`。

---

## 7. 已知限制与后续 TODO

- 当前为**静态移植 + 单元测试**；运行期集成测试（真实 MySQL/Redis/Netty，protobuf JSON 输出格式、Jedis 6 行为差异）尚未覆盖，建议后续补集成测试或用 Testcontainers。
- `netty-all` 为官方不推荐的聚合包，按需可拆为 `netty-handler/netty-codec/netty-transport` 等具体模块以减小体积。
- `com/aengine/tempForFolder.java` 为占位文件，正式开发可移除。

---

## 8. 缺陷修复记录

### 8.1 WebSocket 长连接"10 秒准确断联"（已修复）

- **现象**：WebSocket 接入时连接会在握手后约 10 秒被精确断开，业务侧从未收到"连接成功响应"，客户端因此不会发起心跳。
- **根因**：自定义 `network/netty/websocket/Encoder.write` 对**非 `Packet` 出站消息**（WebSocket 握手的 `101 Switching Protocols` 响应）调用了 `ctx.writeAndFlush(msg)`，**新建了 promise 并丢弃了传入的原始 `promise`**。netty 的 `WebSocketServerProtocolHandshakeHandler` 依赖"101 写操作 promise 完成"来确认握手成功、取消默认 10s 握手超时并触发 `HandshakeComplete` 事件。promise 永不完成 → 10s 后判定握手超时关闭连接、`HandshakeComplete` 永不触发（101 字节其实已发出，故客户端那侧"握手完成"，但服务端的业务 `onConnect`/连接成功响应不会发生）。
- **修复**：
  1. `Encoder`：非 `Packet` 消息透传原始 promise（`ctx.write(msg, promise)`）；`Packet` 分支改用 `ctx.writeAndFlush(frame, promise)`，异常路径 `promise.setFailure(e)`。
  2. `websocket/ServerHandler`：连接创建与 `dispatcher.onConnect` 从 `channelActive`（TCP 连接、握手前）移到 `userEventTriggered` 的 `WebSocketServerProtocolHandler.HandshakeComplete` 事件，确保连接成功响应在 WS 通道真正建立后才下发。
- **验证**：`src/test/java/com/aengine/network/WebSocketLongConnectionTest.java`（独立可运行 `main`，非 CI 用例），原生 netty WS 客户端观察 15s 跨过 10s 阈值并每 4s 心跳。修复前稳定复现 `断开于 ~10037ms`，修复后 `PASS：长连接稳定`。运行：

```bash
mvn -q test-compile \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.aengine.network.WebSocketLongConnectionTest \
  -Dexec.classpathScope=test
```
