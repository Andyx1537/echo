#!/usr/bin/env bash
# 回响 (Echo) · 本地联调一键启动脚本（幂等，可重复执行）
#   - 设 JDK26 / 去掉 ELECTRON_RUN_AS_NODE
#   - 起 PostgreSQL16 (+pgvector)，数据目录 deploy/pgdata，监听 127.0.0.1:5432
#   - 后台常驻启动 echo-server（WebSocket 9001），setsid+nohup，脱离当前会话
#   - 打印 PID 与端口
# 用法：bash start_local.sh
set -uo pipefail

# ---- 环境 ----
unset ELECTRON_RUN_AS_NODE 2>/dev/null || true
export JAVA_HOME=/opt/homebrew/opt/openjdk@26/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

ECHO_ROOT=/Users/andy/Documents/workSpace/Echo
DEPLOY="$ECHO_ROOT/deploy"
SERVER_DIR="$ECHO_ROOT/echo-server"
PGBIN=/opt/homebrew/opt/postgresql@16/bin
PGDATA="$DEPLOY/pgdata"
PGLOG="$DEPLOY/pg.log"
SRV_LOG="$DEPLOY/echo-server.log"
SRV_PIDFILE="$DEPLOY/.echo-server.pid"
CP_FILE="$DEPLOY/.cp.txt"
ECHO_PORT=9001

echo "==================== Echo 本地联调启动 ===================="

# ---- 1) PostgreSQL ----
if "$PGBIN/pg_ctl" -D "$PGDATA" status >/dev/null 2>&1; then
  echo "[PG ] 已在运行，跳过启动。"
else
  echo "[PG ] 启动中..."
  "$PGBIN/pg_ctl" -D "$PGDATA" -l "$PGLOG" \
    -o "-p 5432 -c listen_addresses=127.0.0.1 -c unix_socket_directories=$PGDATA" -w start \
    || { echo "[PG ] 启动失败，见 $PGLOG"; exit 1; }
fi
PG_PID="$(head -1 "$PGDATA/postmaster.pid" 2>/dev/null || echo '?')"

# 等库可连（最多 ~10s）
for i in $(seq 1 20); do
  if "$PGBIN/pg_isready" -h 127.0.0.1 -p 5432 -U echo -d echo >/dev/null 2>&1; then break; fi
  sleep 0.5
done
if "$PGBIN/psql" -h 127.0.0.1 -p 5432 -U echo -d echo -tAc \
     "SELECT extversion FROM pg_extension WHERE extname='vector'" 2>/dev/null | grep -q .; then
  echo "[PG ] echo 库可连，vector 扩展在位。"
else
  echo "[PG ] 警告：echo 库或 vector 扩展异常，请查 $PGLOG 与 RUNBOOK。"
fi

# ---- 2) echo-server ----
# 已有我们托管的进程在跑？
if [ -f "$SRV_PIDFILE" ] && kill -0 "$(cat "$SRV_PIDFILE")" 2>/dev/null; then
  echo "[SRV] 已在运行 (PID $(cat "$SRV_PIDFILE"))，跳过启动。"
else
  # 端口被别的进程占用？（如 IDE 启动的实例）
  FOREIGN="$(lsof -nP -iTCP:$ECHO_PORT -sTCP:LISTEN -t 2>/dev/null | head -1)"
  if [ -n "$FOREIGN" ]; then
    echo "[SRV] 警告：端口 $ECHO_PORT 已被进程 $FOREIGN 占用（非本脚本托管，可能是 IDE 启动）。"
    echo "[SRV] 跳过启动以免冲突。如需用本脚本接管，请先停掉该进程：kill $FOREIGN"
  else
    [ -f "$CP_FILE" ] || { echo "[SRV] 缺少 $CP_FILE，请先 mvn dependency:build-classpath（见 RUNBOOK）"; exit 1; }
    CP="$SERVER_DIR/target/classes:$(cat "$CP_FILE")"
    echo "[SRV] 后台常驻启动中..."
    cd "$SERVER_DIR"
    # 真正常驻的关键：必须脱离当前 shell 的会话/进程组，否则父会话退出会被一并回收。
    # Linux 用 setsid；macOS 默认无 setsid，改用 _daemonize.py(os.setsid)+exec 达到同样效果。
    # nohup 再额外忽略 SIGHUP；stdin 接 /dev/null。exec 后 PID 不变，$! 即守护进程 PID。
    if command -v setsid >/dev/null 2>&1; then
      DETACH=(setsid nohup)
    else
      DETACH=(nohup python3 "$DEPLOY/_daemonize.py")
    fi
    "${DETACH[@]}" java \
      -Decho.db.enabled=true \
      -Decho.db.config="$DEPLOY/echo-db.properties" \
      -Decho.port=$ECHO_PORT \
      -cp "$CP" com.echo.bootstrap.EchoServer \
      > "$SRV_LOG" 2>&1 < /dev/null &
    SRV_PID=$!
    disown "$SRV_PID" 2>/dev/null || true
    echo "$SRV_PID" > "$SRV_PIDFILE"
    # 等端口就绪（最多 ~15s）
    for i in $(seq 1 30); do
      if lsof -nP -iTCP:$ECHO_PORT -sTCP:LISTEN >/dev/null 2>&1; then break; fi
      sleep 0.5
    done
  fi
fi

# ---- 3) 状态汇报 ----
echo "------------------------------------------------------------"
echo "[PG ] PID=$PG_PID  端口=127.0.0.1:5432  数据目录=$PGDATA"
if [ -f "$SRV_PIDFILE" ] && kill -0 "$(cat "$SRV_PIDFILE")" 2>/dev/null; then
  echo "[SRV] PID=$(cat "$SRV_PIDFILE")  端口=ws://0.0.0.0:$ECHO_PORT  日志=$SRV_LOG"
else
  L="$(lsof -nP -iTCP:$ECHO_PORT -sTCP:LISTEN -t 2>/dev/null | head -1)"
  echo "[SRV] 监听 $ECHO_PORT 的进程：${L:-无}（如非本脚本托管见上方提示）"
fi
echo "确认在跑： lsof -nP -iTCP:5432 -sTCP:LISTEN ; lsof -nP -iTCP:$ECHO_PORT -sTCP:LISTEN"
echo "冒烟验证： python3 $DEPLOY/smoke_p1.py"
echo "停止全部： bash $DEPLOY/stop_local.sh"
echo "==========================================================="
