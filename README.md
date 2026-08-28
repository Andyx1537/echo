# echo — 服务端

Echo 的服务端代码与本地运维。

## 目录

| 目录 | 内容 |
| --- | --- |
| `echo-server/` | Java 服务端全部源码；建表脚本在 `src/main/resources/sql/schema.sql` |
| `echo-server/src/main/proto/` | 🔴 **协议副本，不是真源**，见下节 |
| `deploy/` | 本地起服务与数据库：`docker-compose.yml`（pgvector/pg16）、`RUNBOOK.md`、`start_local.sh` 等 |

跑起来看 `deploy/RUNBOOK.md`；构建与测试的坑（`mvn test` 本机挂死等）看 echo-doc 仓的
`docs/BACKEND-RUN.md` §6。

## 🔴 proto 是副本，真源在 echo-doc

产品负责人 2026-08-28 裁定：`.proto` 的**唯一真源是 `echo-doc` 仓的 `proto/`**，
本仓 `echo-server/src/main/proto/` 是一份副本，因为 `pom.xml` 的 protobuf 插件
从 `src/main/proto` 读，搬走后端就编译不过。

**这个安排的已知代价（当时明确知情后仍选定此方案）**：两份不一致时**不会有任何报错** ——
后端照旧编译、照旧通过，只是编出来的协议和真源对不上。这正是本项目反复踩的那类缺陷。

所以：

- 改协议**先改 `echo-doc/proto/`**，再同步到这里，不要反过来；
- 提交前跑一次 `echo-doc` 仓的 `scripts/proto-check.sh`，它比对 sha256，不一致退 1。

## 仓库边界

| 找什么 | 去哪个仓 |
| --- | --- |
| 文档、规格、裁定、比稿图、协议真源 | `echo-doc` |
| H5 前端、Unity 旧工程 | `echo-client` |
| 并行工作线监控 skill | `monitor` |

## 拆分来源

从单仓 `Echo` 于 2026-08-28 按快照拆出，基准 `67a62ae1702322cc051eb240375359e06f6614f8`。
拆分前的改动历史没有带过来（产品负责人裁定），原单仓仍在本地保留。
