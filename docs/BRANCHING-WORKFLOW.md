# Echo 前后端统一分支与发布工作流

## 1. 分支职责

| 分支 | 职责 | 允许的操作 |
|---|---|---|
| `master` | 冻结的主干基线 | 不再承接日常开发、合并或发布提交 |
| `release` | 唯一发布线 | 仅接收验收通过的 `develop` Squash 发布提交及版本 Tag |
| `develop` | 当前版本的唯一开发线 | 所有代码修改、调试、编译、测试和验收均在此完成 |

当前后端分支基线为提交 `17687d1`。`master`、`release` 和首个 `develop` 均从该提交起步。

## 2. 日常开发

1. 开始工作前确认位于 `develop`：

   ```bash
   git switch develop
   git status --short --branch
   ```

2. 代码、Schema、配置、测试和文档修改均提交到 `develop`。
3. 不在 `master` 或 `release` 上调试、修代码或补提交。
4. 数据库修改必须同时提供 Schema 版本、升级 SQL/说明和契约测试。
5. 前后端协议修改必须在同一个版本候选中同步接口字段、错误码与兼容策略。

## 3. 发布验收门禁

只有同时满足以下条件，`develop` 才能申请发布：

- 工作区无遗漏文件，提交边界清楚。
- `git diff --check` 通过。
- `mvn -pl echo-server -am test` 全量通过，不允许以“专项测试通过”代替全量通过。
- 涉及数据库时，旧版本升级与空库初始化均通过，Schema 版本与服务要求一致。
- 涉及核心链路时，登录、建档、空间、向量召回、回声和心跳冒烟通过。
- API、配置、迁移、回滚和运行手册已经更新。
- 前端完成对应版本联调，双方确认接口契约和版本号。
- 版本 Tag 名称由负责人明确指定；未经负责人确认不得自行决定版本号。

任一门禁失败，修改继续留在 `develop`，不得进入 `release`。

## 4. 发布流程：Squash 到 release

负责人指定版本号（示例 `v0.2.0`）并确认发布后，由发布主管执行：

```bash
git switch release
git pull --ff-only origin release
git merge --squash develop
git commit -m "release: v0.2.0"
git tag -a v0.2.0 -m "Echo v0.2.0"
git push origin release
git push origin v0.2.0
```

约束：

- `release` 每个版本只有一个经过压缩的发布提交。
- Tag 必须打在 `release` 的发布提交上，不能打在旧 `develop` 上。
- 禁止普通 merge commit、直接修改 `release`、直接向 `release` 补丁式提交。
- 发布内容、Tag 和回滚点必须三者一致。

## 5. 发布后重建 develop

由于发布采用 Squash，旧 `develop` 与 `release` 历史不再相同。Tag 推送成功后，从该 Tag
重新创建下一周期的 `develop`：

```bash
git switch release
git branch -D develop
git switch -c develop v0.2.0
git push --force-with-lease -u origin develop
```

执行前必须确认：

- 旧 `develop` 的所有目标修改均已进入本次 `release`。
- 工作区没有未提交修改。
- Tag 已在远端可见。
- 前后端成员已停止基于旧 `develop` 推送。

重建完成后，所有成员重新同步 `develop`，不要把旧开发历史再次合入：

```bash
git fetch origin --tags
git switch develop
git reset --hard origin/develop
```

`reset --hard` 仅适用于成员已经确认本地没有未提交工作的情况。

## 6. 前后端同步规则

前后端采用相同的三线语义和同一个产品版本号：

- 后端和前端的 `master` 都冻结，不参与日常开发。
- 双方只在各自 `develop` 上进行当前版本开发和联调。
- 只有双方验收完成后，才分别 Squash 到各自 `release`。
- 同一次产品发布使用相同 Tag，例如双方都使用 `v0.2.0`。
- 若一端未通过验收，两端都不生成正式发布 Tag。
- 每次发布后，双方都从各自 `release` 的当前 Tag 重建 `develop`。

## 7. 异常处理

- 线上紧急问题仍先在新一周期 `develop` 修复并验收，再按相同流程进入 `release`。
- 未经负责人明确授权，不直接在 `release` 做 hotfix。
- Tag 打错不得移动或覆盖；使用新的修订版本 Tag。
- 已推送的 `release` 禁止强推；只有重建 `develop` 时允许受控使用 `--force-with-lease`。
