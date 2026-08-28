#!/usr/bin/env python3
"""把后续命令在“新会话”中 exec 起来，等价于 Linux 的 setsid。

macOS 默认无 setsid；本脚本用 os.setsid() 让目标进程脱离当前 shell 的进程组/会话，
从而在父 shell（或调用它的工具会话）退出后不被一并回收，实现真正常驻。
用法：python3 _daemonize.py <cmd> [args...]
exec 后 PID 不变，调用方可用 $! 记录该 PID。
"""
import os
import sys

if len(sys.argv) < 2:
    sys.stderr.write("usage: _daemonize.py <cmd> [args...]\n")
    sys.exit(2)

try:
    os.setsid()  # 新建会话，脱离原进程组（非交互脚本中后台进程非组长，必成功）
except OSError:
    pass  # 已是会话/组长时忽略，继续 exec

os.execvp(sys.argv[1], sys.argv[1:])
