# Echo 数据架构整改报告

## 结论

Echo 保留 Aengine 的 Repository 架构，并由 Aengine 底层提供可切换 SQL 方言、连接池、事务和
Schema 策略；Echo 不再维护一套平行的 PostgreSQL Repository。关系数据与向量数据统一落在
PostgreSQL，向量列由 pgvector 专用通道维护。

## 当前结构

- 业务仓储：`CachedJDBCRepository`，负责关系实体 CRUD 与缓存。
- 数据引擎：Aengine `DB`，负责 HikariCP、SQL 方言、事务和生命周期。
- SQL 方言：默认 MySQL，可通过 `db.dialect=postgresql` 切换 PostgreSQL。
- Schema 策略：`none | validate | update`；Echo 标准环境固定使用 `validate`。
- Schema 真源：`src/main/resources/sql/schema.sql`，当前版本 `2026083102`。
- 向量引擎：`PgVectorStore`，使用 `vector(768)`、余弦距离和 HNSW 索引。
- 模型隔离：向量按 provider/model/version 过滤，避免模型切换后混合召回。
- 模型回填：`VectorRebuildService` 显式分批执行，不在启动时扫描全表。

## 已完成整改

1. Aengine 增加 PostgreSQL 方言、事务 API、连接池关闭和方言测试。
2. Echo 九个领域仓储迁移至 Aengine `CachedJDBCRepository`。
3. 删除 Echo 重复的 `PgRepository` 与 `CachedPgRepository`。
4. 增加 Schema 模式及启动版本校验；DB 开启时改为失败即停。
5. 增加向量模型元数据、维度校验、模型隔离索引和 HNSW 索引。
6. 修复 JDK 26 下 Mockito agent 与离屏 ImageIO 测试环境。

## 数据库验收

- PostgreSQL 16 与 pgvector 0.8.0 已完成本机落库。
- Schema 版本：`2026083102`。
- `t_self_vector` 维度：768。
- HNSW：`vector_cosine_ops (m=16, ef_construction=64)`。
- 两账号端到端验收成功，第二账号可召回重启前持久化的第一账号。
- 向量、账号、意识档案关联检查无孤儿数据。

## 仍需产品线另行处理

- HTTP 测试夹具尚未适配“手机号绑定前置”规则，导致一批既有 HTTP 用例失败；该问题与本次数据
  架构整改无关，不应通过放松生产校验解决。
- `schema.sql` 当前适合作为基线初始化脚本。进入多环境连续发布前，建议在下一项独立工作中引入
  不可变增量迁移目录与发布流水线校验，而不是反复执行整份基线。
- `ResonanceRecord` 已标记废弃但仍参与编译，后续确认无调用后可单独删除。

## 标准运行约束

- 从仓库根目录使用 Maven reactor 构建：`mvn -pl echo-server -am test`。
- 生产配置使用 `db.dialect=postgresql` 与 `db.schemaMode=validate`。
- 升级数据库后才启动新版本服务；服务不会自动修改生产 Schema。
- 切换 embedding provider/model/version 后必须显式安排分批向量回填。
