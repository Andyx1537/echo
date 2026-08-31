# Aengine 架构说明

Aengine 由 `game-engine`（原包 `com.chitu.engine`）平移而来，是一套自研游戏服务端引擎，
基础包重命名为 `com.aengine`，运行环境升级到 **JDK 26 + Maven**，依赖全部采用最新稳定版。

## 模块总览

```
com.aengine
├── persistence      持久化 / 仓储（引擎核心）
│   ├── annotation   @Table/@Column/@Pk/@Index/@Cache/@Fk/@MappedSuperclass/@CRepository
│   ├── db           JDBC 实现（DB/DBManager/CachedJDBCRepository/Delayed/Rolling）
│   ├── redis        Redis 实现（Redis/RedisRepository/Cached/Delayed）
│   └── cache        持久化层缓存接口
├── network          网络层
│   ├── netty        Netty 抽象（IConnection/ISocketServer/Packet/EventDispatcher）+ tcp/udp/websocket
│   └── support      会话与消息分发（PlayerSession/PacketHandlerManager/HandlerMethod...）
├── scheduler        定时任务（Scheduler/CronTrigger/TriggerTask）
├── template         配置表/模板（POI 读 Excel + 代码生成）
├── event            事件总线（EventBusImpl + @EventHandleMethod）
├── cache            通用缓存（CacheManager/LRUCache/SimpleCache）
└── util             工具集（pubsub/lock/thread/id/collection/clazz/xml/yml/Gson/Http...）
```

## 核心设计

### 1. 持久化 / 仓储（repo）—— class table 封装
- 实体实现标记接口 `AbstractEntity`，通过注解声明表结构。
- `TableMeta.parse(Class)` 用**反射 + 注解**把实体类解析成表结构元数据
  （列 `ColumnMeta`、索引 `IndexMeta`、缓存 `CacheMeta`、主键、分片 `cluster`）。
- `IRepository<T>` 是统一仓储接口（add/remove/get/list/save/truncate/forceSave）。
- `CachedRepository` 提供二级缓存：实体缓存 + 索引缓存 `CacheIndex`，配合
  `ReferenceCountedLockManager` 引用计数锁保证并发一致性。
- 两套落地实现：JDBC（HikariCP 连接池）与 Redis（Jedis）。

### 2. 网络层（network）
- `Packet` 定义统一包头（TCP/UDP 标志、是否需要 ACK、协议类型 protobuf/json）。
- `ISocketServer/IConnection/ITcpClient` 抽象 + `AbstractSocketServer/AbstractConnection`
  模板，分别由 tcp/udp/websocket 三套 Encoder/Decoder/Handler 实现。
- `support` 层负责会话（`PlayerSession/ClientSession`）与消息分发
  （`PacketHandlerManager` + `@HandlerMethod`）。

### 3. class table 扫描
- `util.clazz.ClassUtil` 扫描包/jar 下的类，配合注解自动注册仓储、handler、事件接收者。

### 4. 其它子系统
- **scheduler**：基于 `ScheduledThreadPoolExecutor` 的定时器 + Cron 触发器。
- **template**：POI 读取策划配置表，`GeneratorClassUtil` 生成配置类。
- **event**：注解驱动事件总线，支持同步/异步/分布式锁。
- **cache**：`CacheManager` 定时清理过期项，`LRUCache/SimpleCache` 两种实现。
- **util**：pubsub（Redis 发布订阅 + 跨服）、各类锁、线程池、ID 生成、序列化等。

## 技术栈

| 领域 | 选型 |
|---|---|
| 语言/构建 | JDK 26 + Maven |
| 网络 | Netty 4.2.x |
| 序列化 | protobuf 4.x + protobuf-java-util、Gson |
| 数据库 | MySQL（mysql-connector-j）+ HikariCP |
| 缓存 | Redis（Jedis 6.x）、concurrentlinkedhashmap-lru |
| 配置 | snakeyaml、commons-io |
| Excel | Apache POI 5.x |
| 日志 | slf4j + logback |
| 样板代码 | Lombok |

详细的依赖与 API 升级差异见 [MIGRATION.md](./MIGRATION.md)。
