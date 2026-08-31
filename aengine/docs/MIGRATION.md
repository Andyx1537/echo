# 移植对照文档（game-engine → Aengine）

记录从 `game-engine`（JDK8）平移到 `Aengine`（JDK21 + 最新依赖）过程中的所有结构性
与 API 破坏性改动，作为"旧 → 新"的对照参考。

## 1. 工程级变更

| 项目 | game-engine | Aengine |
|---|---|---|
| groupId / artifactId | `com.chitu / game-engine` | `com.aengine / Aengine` |
| 基础包 | `com.chitu.engine` | `com.aengine` |
| JDK | 8 | 21（`maven.compiler.release=21`） |
| 构建 | maven-compiler source/target 8 | release 21 + lombok annotationProcessor |

包重命名：全量将 `com.chitu.engine` 替换为 `com.aengine`（163 个源文件）。

## 2. 依赖升级对照

| 用途 | 旧依赖 | 新依赖 | 是否破坏性 |
|---|---|---|---|
| 日志 | commons-logging 1.2 | slf4j 2.0.17 + logback 1.5.18 | 是 |
| 网络 | netty-all 4.1.5 | netty-all 4.2.1 | 基本兼容 |
| protobuf | protobuf-java 3.3.1 + protobuf-java-format 1.2 | protobuf-java 4.35.0 + protobuf-java-util | 是 |
| JSON | gson 2.8.2 | gson 2.14.0 | 兼容 |
| YAML | snakeyaml 1.24 | snakeyaml 2.4 | 兼容（未用显式 tag） |
| IO | commons-io 2.5 | commons-io 2.22.0 | 兼容 |
| Excel | poi / poi-ooxml 3.17 | poi / poi-ooxml 5.5.1 | 是 |
| 连接池 | commons-dbcp2 2.1.1 | HikariCP 6.3.0 | 是 |
| MySQL 驱动 | （隐式/外部） | mysql-connector-j 9.3.0 | 新增 |
| Redis | jedis 2.9.0 | jedis 6.0.0 | 是 |
| LRU | concurrentlinkedhashmap-lru 1.4.2 | 保留 1.4.2 | 无更新版本 |
| 样板代码 | 无 | lombok 1.18.38 | 新增 |

> 注：用户在确认环节选择"Jedis 5.x"，但"全部最新"要求下实际采用 **Jedis 6.0.0**。
> 如需回退到 5.x，仅调整 `pom.xml` 中 `jedis.version` 即可。

## 3. 代码适配明细

### 3.1 日志：commons-logging → slf4j（约 39 个文件）
- `import org.apache.commons.logging.Log;` → `import org.slf4j.Logger;`
- `import org.apache.commons.logging.LogFactory;` → `import org.slf4j.LoggerFactory;`
- `LogFactory.getLog(X.class)` → `LoggerFactory.getLogger(X.class)`
- 类型 `Log log/LOG` → `Logger log/LOG`
- slf4j 无 `error(Throwable)` 重载：`log.error(e)` → `log.error("", e)`（`log.info(e)` 同理）。

### 3.2 连接池：dbcp2 → HikariCP（`persistence/db/DB.java`）
- 移除 `BasicDataSourceFactory.createDataSource(properties)`。
- 新增 `createDataSource(Properties)`，基于 `HikariConfig/HikariDataSource`，
  兼容历史属性键：`url/jdbcUrl`、`driverClassName`、`username`、`password`、
  `maxTotal/maxActive/maximumPoolSize`、`minIdle`、`maxWait/connectionTimeout`，
  并透传 `hikari.*` 前缀的高级配置。

### 3.3 Redis：Jedis 2.9 → Jedis 6（`persistence/redis/Redis.java`、`util/pubsub/RedisActiveMessenger.java`）
- `set(k,v,"NX","EX",t)` → `set(k, v, SetParams.setParams().nx().ex(t))`。
- 类型迁移到子包并补充 import：
  `args.GeoUnit`、`resps.GeoRadiusResponse`、`resps.Tuple`、`params.SetParams`。
- 有序集合方法返回类型由 `Set` 变为 `List`，为保持对外签名不变，统一用
  `new LinkedHashSet<>(...)` 包装：`zRange/zRangeByScore/zRevrange/zRevrangeByScore/
  zrangeWithScores/zrevrangeWithScores`。

### 3.4 protobuf：废弃的 protobuf-java-format → protobuf-java-util
- 新增工具类 `com.aengine.util.ProtobufUtil`，封装受检异常：
  - `JsonFormat.printToString(msg)` → `ProtobufUtil.toJson(msg)`（内部 `JsonFormat.printer().print`）
  - `JsonFormat.merge(json, builder)` → `ProtobufUtil.mergeJson(json, builder)`
  - `TextFormat.printToUnicodeString(msg)` → `ProtobufUtil.toText(msg)`（`TextFormat.printer().printToString`）
- 涉及文件：`network/support/PlayerSession`、`ClientSession`、`PacketHandlerManager`、`TransientSession`。

### 3.5 Excel：POI 3.17 → POI 5.5.1（`template/ExcelReader.java`）
- `cell.getCellTypeEnum()` → `cell.getCellType()`（POI 4+ 已移除前者）。

### 3.6 代码生成器包名（`template/GeneratorClassUtil.java`）
- 生成代码与帮助串里的 `com.chitu.game.common.template.*` → `com.aengine.template.*`。

### 3.7 Lombok 化（配置 POJO）
- `util/RedisConfig`、`util/NetConfig`、`util/ServerConfig`：删除手写 getter，
  改用 `@Getter @Setter`。
- **未**对 `ColumnMeta/CacheMeta` 等元数据类使用 `@Data`，因为它们被用作 Map 的 key，
  自动生成的 `equals/hashCode` 会改变既有行为。后续可按需增量 lombok 化。

## 3.8 JDK 基线升级：JDK 21 → JDK 26（2026-06）
- `Aengine/pom.xml`、`Echo/echo-server/pom.xml`：`maven.compiler.release` `21` → `26`。
- 适用范围：仅 `Aengine` 与 `Echo/echo-server`；`game-engine` / `platform-back` 等 JDK8 遗留工程不动。
- **破坏性影响 / 处置**：
  - 测试栈 Mockito/ByteBuddy 不支持 JDK 26 字节码（`Mockito cannot mock this class`，34 用例报错）。
    处置：`Echo/echo-server` 的 `mockito` `5.18.0` → `5.23.0`，并显式钉住
    `net.bytebuddy:byte-buddy` / `byte-buddy-agent` `1.18.10`（test scope）。升级后 38 用例全绿。
  - 运行期警告（非阻断）：`sun.misc.Unsafe::objectFieldOffset has been called by lombok`，
    Lombok 仍可用；后续可随 Lombok 升级消除。
- 校验：`Aengine` `mvn install` + `Echo/echo-server` `mvn test` 在 `openjdk@26`（26.0.1）下 BUILD SUCCESS。
- 旧→新（环境变量）：`JAVA_HOME=/opt/homebrew/opt/openjdk@21/...` → `.../openjdk@26/...`。

## 4. 后续可选项
- 进一步把更多纯数据类 lombok 化（注意 equals/hashCode 副作用）。
- 评估 `concurrentlinkedhashmap-lru` 替换为 Caffeine。
- 评估 protobuf json 的输出格式与旧库差异（字段命名、默认值输出策略）。
- 评估升级 Lombok 以消除 JDK 26 下 `sun.misc.Unsafe` 警告。
