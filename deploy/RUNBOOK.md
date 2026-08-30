# 回响 (Echo) · P1 一键启动手册（RUNBOOK）

目标：起库（PostgreSQL + pgvector）→ 初始化 schema → 启动 `EchoServer`（WebSocket 9001）→ 跑通 P1 核心链路冒烟。

> 本手册经本机实测跑通（macOS / arm64 / JDK26）。本机**未安装 Docker**，所以实测走的是
> **方案 B（brew 原生 PostgreSQL）**；有 Docker 的同学可用**方案 A**，两者库结构与连接参数完全一致。

新增/相关文件（均在 `deploy/`）：

| 文件 | 作用 |
|---|---|
| `docker-compose.yml` | 方案 A：`pgvector/pgvector:pg16` 一键起库（含 schema 自动初始化） |
| `echo-db.properties` | echo-server 的 DB 配置（按 `EchoDatabase`/`PgDb` 的**真实读法**编写） |
| `smoke_p1.py` | P1 链路冒烟脚本（纯标准库，无第三方依赖） |
| `RUNBOOK.md` | 本手册 |
| `pgdata/`（方案 B 生成） | brew 原生集群数据目录（自包含在工程内，便于清理） |

实际用到的配置键（与代码对齐）：

- 系统属性（`com.echo.bootstrap.EchoDatabase` / `EchoServer`）：
  - `-Decho.db.enabled=true`：开启 DB（默认 false 时只注册心跳）。
  - `-Decho.db.config=<path>`：DB 配置文件路径（properties）。
  - `-Decho.port=9001`：WebSocket 端口（也可用首个程序参数）。
  - `-Decho.host=0.0.0.0`（可选）、`-Decho.workerId=1`（可选，雪花 ID 机器位）。
- DB 配置文件键（`com.echo.infra.persistence.PgDb` 真实解析）：
  - `db.name=echo` —— **必须**与各仓储 `@CRepository(source="echo")` 一致（数据源注册名）。
  - `jdbcUrl=jdbc:postgresql://127.0.0.1:5432/echo`
  - `driverClassName=org.postgresql.Driver`
  - `username=echo` / `password=echo`
  - `maximumPoolSize=8`

公共环境变量（每个新开终端都先执行）：

```bash
# JDK26（务必去掉 ELECTRON_RUN_AS_NODE，否则 java 会被当成 node 启动）
unset ELECTRON_RUN_AS_NODE
export JAVA_HOME=/opt/homebrew/opt/openjdk@26/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

export ECHO=/Users/andy/Documents/workSpace/Echo
export DEPLOY=$ECHO/deploy
```

---

## 方案 A：Docker（首选，若本机有 Docker）

```bash
# 1) 起库（pgvector/pgvector:pg16；库 echo / 账号 echo / 密码 echo / 端口 5432）
cd $DEPLOY
docker compose up -d

# 2) schema 已由 compose 的 /docker-entrypoint-initdb.d 自动执行（首次创建卷时）。
#    若卷已存在或想手动重跑：
docker compose exec -T db psql -U echo -d echo < $ECHO/echo-server/src/main/resources/sql/schema.sql

# 3) 校验
docker compose exec db psql -U echo -d echo -c "\dt"
docker compose exec db psql -U echo -d echo -c "SELECT extname,extversion FROM pg_extension WHERE extname='vector';"
```

然后跳到 **第 3 步：构建并启动 EchoServer**。停库：`docker compose down`（删数据加 `-v`）。

---

## 方案 B：brew 原生 PostgreSQL + pgvector（本机实测路径）

### B.1 安装（一次性）

```bash
# postgresql@16
brew install postgresql@16

# pgvector：brew 的 pgvector 瓶子目前只为 pg17/pg18 预编译，pg16 需源码编译安装：
export PATH="/opt/homebrew/opt/postgresql@16/bin:$PATH"   # 让 pg_config 指向 pg16
cd /tmp && rm -rf pgvector-build
git clone --depth 1 --branch v0.8.0 https://github.com/pgvector/pgvector.git pgvector-build
cd pgvector-build && make && make install     # 装进 pg16 的 extension 目录
```

### B.2 初始化集群 + 起库（自包含数据目录，便于清理）

```bash
export PGBIN=/opt/homebrew/opt/postgresql@16/bin
export PGDATA=$DEPLOY/pgdata

# 初始化集群，超级用户取名 echo（本地 trust 认证，便于原型联调）
"$PGBIN/initdb" -U echo --auth-local=trust --auth-host=trust -E UTF-8 "$PGDATA"

# 起库（监听 127.0.0.1:5432，socket 放数据目录内）
"$PGBIN/pg_ctl" -D "$PGDATA" -l "$PGDATA/server.log" \
  -o "-p 5432 -c listen_addresses=127.0.0.1 -c unix_socket_directories=$PGDATA" -w start

# 设置 echo 的密码（与 echo-db.properties 一致；trust 模式下密码不参与校验但保持一致）
"$PGBIN/psql" -h 127.0.0.1 -p 5432 -U echo -d postgres -c "ALTER ROLE echo WITH PASSWORD 'echo';"

# 建库 echo（已存在则跳过）
"$PGBIN/psql" -h 127.0.0.1 -p 5432 -U echo -d postgres -tc \
  "SELECT 1 FROM pg_database WHERE datname='echo'" | grep -q 1 \
  || "$PGBIN/createdb" -h 127.0.0.1 -p 5432 -U echo -O echo echo
```

### B.3 初始化 schema（含 CREATE EXTENSION vector + 建表 + vector(768)）

```bash
"$PGBIN/psql" -h 127.0.0.1 -p 5432 -U echo -d echo -v ON_ERROR_STOP=1 \
  -f $ECHO/echo-server/src/main/resources/sql/schema.sql

# 校验：9 张表 / vector 扩展 / embedding 维度
"$PGBIN/psql" -h 127.0.0.1 -p 5432 -U echo -d echo -c "\dt"
"$PGBIN/psql" -h 127.0.0.1 -p 5432 -U echo -d echo -c \
  "SELECT extname,extversion FROM pg_extension WHERE extname='vector';"
```

---

## 第 3 步：构建并启动 EchoServer（WebSocket 9001）

```bash
# 3.1 先把引擎 Aengine 装进本地 Maven 仓库（echo-server 依赖它）
cd /Users/andy/Documents/workSpace/Aengine
mvn -q -DskipTests install

# 3.2 编译 echo-server，并导出运行期 classpath
cd $ECHO/echo-server
mvn -q -DskipTests compile
mvn -q -DskipTests dependency:build-classpath -Dmdep.outputFile=$DEPLOY/.cp.txt

# 3.3 启动（开 DB + pgvector 实现），日志写到 echo-server.log
cd $ECHO/echo-server
CP="target/classes:$(cat $DEPLOY/.cp.txt)"
java -Decho.db.enabled=true \
     -Decho.db.config=$DEPLOY/echo-db.properties \
     -Decho.port=9001 \
     -cp "$CP" com.echo.bootstrap.EchoServer | tee $DEPLOY/echo-server.log
```

启动成功的关键日志：

```
HikariPool-1 - Start completed.
PostgreSQL 数据源已注册: name=echo
已注册 Handler: Heartbeat(9001) + Login(1001) + MindProfile(1201) + Space(1301/1303) + Resonance(1401) + Echo(1501/1503)
EchoServer WebSocket 已启动: ws://0.0.0.0:9001
```

> schema 已就绪时，`PgRepository` 通过 `information_schema` 探测到表已存在，不会重复建表。

**长跑**：上面的命令在前台运行。要后台长跑，用：

```bash
nohup java -Decho.db.enabled=true -Decho.db.config=$DEPLOY/echo-db.properties \
  -Decho.port=9001 -cp "$CP" com.echo.bootstrap.EchoServer \
  > $DEPLOY/echo-server.log 2>&1 &
echo $! > $DEPLOY/.echo-server.pid     # 记下 PID 便于停服
```

---

## 第 4 步：跑冒烟（验证整条 P1 链路）

另开一个终端（无需 JDK 环境，纯 Python 标准库）：

```bash
cd /Users/andy/Documents/workSpace/echo/deploy
python3 smoke_p1.py                       # 默认连 127.0.0.1:9001
# 或：python3 smoke_p1.py --host 127.0.0.1 --port 9001
```

冒烟会依次发：登录 `1001`→`1002`、提交偏好 `1201`→`1202`、进入空间 `1301`→`1302`、
查共鸣 `1401`→`1402`、留痕 `1503`→`1504`、拉回声 `1501`→`1502`、心跳 `9001`→`9002`，
并打印每步请求/响应。末尾出现 `冒烟全部通过：P1 核心链路连通` 即成功。

封包格式（脚本严格按此实现）：`head(1)=0x81(TCP|JSON) | length(int16 BE)=body+4 | cmd(int32 BE) | body(JSON)`；
JSON 为 proto3 风格：lowerCamelCase、默认值省略、**int64 用字符串**（如 `accountId`/`ownerSpaceId`）。

---

## 停服 / 停库 / 清理

```bash
# 停 EchoServer（前台用 Ctrl-C；后台用 PID）
kill "$(cat $DEPLOY/.echo-server.pid)" 2>/dev/null

# 停库
#  方案 A：cd $DEPLOY && docker compose down      （删数据再加 -v）
#  方案 B：
/opt/homebrew/opt/postgresql@16/bin/pg_ctl -D $DEPLOY/pgdata -m fast stop

# 彻底清理方案 B 数据（谨慎，会清空库）
rm -rf $DEPLOY/pgdata
```

---

## 常见问题（FAQ）

1. **`ELECTRON_RUN_AS_NODE` 导致 `java` 行为异常**
   该变量会让某些包装过的 `java` 以 Node 模式启动。启动前务必 `unset ELECTRON_RUN_AS_NODE`。

2. **端口被占用（9001 / 5432）**
   ```bash
   lsof -nP -iTCP:9001 -sTCP:LISTEN
   lsof -nP -iTCP:5432 -sTCP:LISTEN
   ```
   换端口：服务端用 `-Decho.port=<port>` 并让冒烟脚本 `--port` 跟随；库换端口需同步改 `echo-db.properties` 的 `jdbcUrl`。

3. **`ERROR: extension "vector" is not available`（pgvector 扩展缺失）**
   pg16 没装到 pgvector。brew 的 `pgvector` 瓶子只覆盖 pg17/pg18，**pg16 必须源码编译**（见 B.1）。
   编译时 `pg_config` 必须指向 pg16：`export PATH="/opt/homebrew/opt/postgresql@16/bin:$PATH"` 后再 `make && make install`。
   （或改用方案 A 的 `pgvector/pgvector:pg16` 镜像，扩展已内置。）

4. **JDK 版本**
   必须 JDK26。校验：`java -version` 应为 `26.x`。`JAVA_HOME` 指向
   `/opt/homebrew/opt/openjdk@26/libexec/openjdk.jdk/Contents/Home`。

5. **连库失败 / 启动后无业务 Handler**
   - 看日志是否有 `PostgreSQL 数据源已注册: name=echo`；没有则检查 `-Decho.db.enabled=true` 与 `-Decho.db.config` 路径。
   - `PgDb` 惰性连接（`initializationFailTimeout=-1`），库没起来也能启动到 WebSocket，但登录会失败——确认库在 5432 且 schema 已建。
   - 日志出现「未接入 DB，仅注册心跳 Handler」说明 DB 没开成功，按上面两点排查。

6. **`mvn install` 阶段报 `Operation not permitted`**
   多为对 `~/.m2` 无写权限或被沙箱拦截，确保以正常用户权限执行（非只读环境）。

7. **`make install`（pgvector）写 `/opt/homebrew` 失败**
   确保对 Homebrew 目录有写权限（`brew` 正常可用即可）。

## dev-only 路由开关（`echo.devRoutes`）

`DELETE /pet/me`（整只宠物重置）等联调端点**只在显式开启时挂载**：

```bash
java -Decho.devRoutes=true -jar echo-server.jar
```

🔴 **2026-08-30 改过一次，行为变了。** 此前是 `devRoutes = !persistent || <显式开关>`，
即**数据库连不上时自动挂载**——生产环境 PG 抖一下就会把重置端点暴露出去。
这是 fail-open：故障状态反而多给权限。「连不上库」和「这是开发机」是两件事，
不能用同一个布尔量表示。

**对本地联调的影响**：以前不连 PG 就自动有这些端点，现在要自己带参数。
内存态启动且未开开关时，日志里会有一条 `[bootstrap]` 警告提示怎么开。
