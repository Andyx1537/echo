#!/usr/bin/env bash
# 回响 (Echo) · 本地联调一键停止脚本（幂等，可重复执行）
#   - 停 echo-server（按 pidfile）
#   - 停 PostgreSQL（pg_ctl -m fast）
# 用法：bash stop_local.sh
set -uo pipefail

ECHO_ROOT=/Users/andy/Documents/workSpace/Echo
DEPLOY="$ECHO_ROOT/deploy"
PGBIN=/opt/homebrew/opt/postgresql@16/bin
PGDATA="$DEPLOY/pgdata"
SRV_PIDFILE="$DEPLOY/.echo-server.pid"
ECHO_PORT=9001

echo "==================== Echo 本地联调停止 ===================="

# ---- 1) echo-server ----
if [ -f "$SRV_PIDFILE" ] && kill -0 "$(cat "$SRV_PIDFILE")" 2>/dev/null; then
  SRV_PID="$(cat "$SRV_PIDFILE")"
  echo "[SRV] 停止 PID $SRV_PID ..."
  kill "$SRV_PID" 2>/dev/null || true
  for i in $(seq 1 20); do kill -0 "$SRV_PID" 2>/dev/null || break; sleep 0.5; done
  kill -0 "$SRV_PID" 2>/dev/null && { echo "[SRV] 优雅停止超时，强制 kill -9"; kill -9 "$SRV_PID" 2>/dev/null || true; }
  rm -f "$SRV_PIDFILE"
  echo "[SRV] 已停止。"
else
  echo "[SRV] 无本脚本托管的进程（pidfile 缺失或进程已退）。"
  FOREIGN="$(lsof -nP -iTCP:$ECHO_PORT -sTCP:LISTEN -t 2>/dev/null | head -1)"
  [ -n "$FOREIGN" ] && echo "[SRV] 注意：端口 $ECHO_PORT 仍被进程 $FOREIGN 占用（非本脚本托管，如 IDE 启动，需手动停）。"
  rm -f "$SRV_PIDFILE" 2>/dev/null || true
fi

# ---- 2) PostgreSQL ----
if "$PGBIN/pg_ctl" -D "$PGDATA" status >/dev/null 2>&1; then
  echo "[PG ] 停止中..."
  "$PGBIN/pg_ctl" -D "$PGDATA" -m fast stop || true
  echo "[PG ] 已停止。"
else
  echo "[PG ] 未在运行。"
fi

echo "==========================================================="
