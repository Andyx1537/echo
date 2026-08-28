#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
回响 (Echo) · P1 核心链路冒烟脚本（轻量验证工具，仅用 Python 标准库，无第三方依赖）。

按 TECH-P1 §3.0 线上封包格式与服务端真实编解码（Aengine WebSocket Decoder/Encoder）逐步打通：

    +---------+-------------------+----------------+--------------------+
    | head:1  | length:2 (int16)  | cmd:4 (int32)  | body:(length-4)    |   大端/网络字节序
    +---------+-------------------+----------------+--------------------+
    length = body.length + 4；整帧 = 7 + body.length
    head: bit7=TCP(0x80) bit1-0=协议位(0=protobuf/1=JSON)；客户端发包 head=0x81(TCP|JSON)
    body: proto3 JSON —— lowerCamelCase、默认值省略、int64 用字符串承载

链路（§3.1 / §4）：登录 1001→1002 / 提交偏好 1201→1202 / 进入空间 1301→1302 /
查共鸣 1401→1402 / 留痕 1503→1504 / 拉回声 1501→1502 / 心跳 9001→9002。

用法：python3 smoke_p1.py [--host 127.0.0.1] [--port 9001]
"""
import argparse
import base64
import json
import os
import socket
import struct
import sys
import time

HEAD_TCP_JSON = 0x81  # 发包：TCP(0x80) | JSON(0x01)

# ---------------------------------------------------------------------------
# 最小 WebSocket 客户端（RFC6455）：握手 + 二进制帧收发（客户端帧必须掩码）
# ---------------------------------------------------------------------------
class WsClient:
    def __init__(self, host, port, path="/", timeout=10.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        self.buf = b""
        self._handshake(host, port, path)

    def _handshake(self, host, port, path):
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "\r\n"
        )
        self.sock.sendall(req.encode())
        # 读取握手响应（直到空行）
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("握手期间连接被关闭")
            resp += chunk
        header, _, rest = resp.partition(b"\r\n\r\n")
        if b"101" not in header.split(b"\r\n")[0]:
            raise ConnectionError("WebSocket 握手失败:\n" + header.decode(errors="replace"))
        self.buf += rest  # 握手后可能已粘了数据帧

    def send_binary(self, payload: bytes):
        fin_op = 0x82  # FIN=1, opcode=0x2(binary)
        mask_key = os.urandom(4)
        n = len(payload)
        header = bytes([fin_op])
        if n < 126:
            header += bytes([0x80 | n])
        elif n < 65536:
            header += bytes([0x80 | 126]) + struct.pack("!H", n)
        else:
            header += bytes([0x80 | 127]) + struct.pack("!Q", n)
        header += mask_key
        masked = bytes(b ^ mask_key[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(header + masked)

    def _recv_more(self):
        chunk = self.sock.recv(4096)
        if not chunk:
            raise ConnectionError("连接被服务端关闭")
        self.buf += chunk

    def _read_exact(self, n):
        while len(self.buf) < n:
            self._recv_more()
        data, self.buf = self.buf[:n], self.buf[n:]
        return data

    def recv_binary(self) -> bytes:
        """读取一个完整的（服务端不掩码的）WebSocket 数据帧，返回 payload。"""
        while True:
            b0 = self._read_exact(1)[0]
            opcode = b0 & 0x0F
            b1 = self._read_exact(1)[0]
            masked = b1 & 0x80
            length = b1 & 0x7F
            if length == 126:
                length = struct.unpack("!H", self._read_exact(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", self._read_exact(8))[0]
            mask_key = self._read_exact(4) if masked else None
            payload = self._read_exact(length)
            if mask_key:
                payload = bytes(b ^ mask_key[i % 4] for i, b in enumerate(payload))
            if opcode == 0x8:  # close
                raise ConnectionError("服务端发送 Close 帧")
            if opcode in (0x9, 0xA):  # ping/pong，忽略
                continue
            return payload  # 二进制 / 文本帧

    def close(self):
        try:
            self.sock.sendall(bytes([0x88, 0x80]) + os.urandom(4))  # masked close
        except OSError:
            pass
        self.sock.close()


# ---------------------------------------------------------------------------
# Echo 封包 编/解（head|length|cmd|body）
# ---------------------------------------------------------------------------
def pack(cmd: int, body_obj: dict) -> bytes:
    body = json.dumps(body_obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    length = len(body) + 4  # 含 cmd 4 字节
    return struct.pack("!BhI", HEAD_TCP_JSON, length, cmd) + body


def unpack(payload: bytes):
    head = payload[0]
    length = struct.unpack("!h", payload[1:3])[0]
    cmd = struct.unpack("!I", payload[3:7])[0]
    body = payload[7:]
    obj = {}
    if body:
        try:
            obj = json.loads(body.decode("utf-8"))
        except json.JSONDecodeError:
            obj = {"_raw": body.decode("utf-8", errors="replace")}
    return head, cmd, obj


def call(ws: WsClient, step: str, cmd: int, body: dict, expect_cmd: int):
    print(f"\n=== [{step}] 请求 cmd={cmd} ===")
    print("  -> " + json.dumps(body, ensure_ascii=False))
    ws.send_binary(pack(cmd, body))
    head, rcmd, robj = unpack(ws.recv_binary())
    print(f"  <- 响应 head=0x{head:02x} cmd={rcmd}")
    print("     " + json.dumps(robj, ensure_ascii=False))
    ok = rcmd == expect_cmd and int(robj.get("code", 0)) == 0
    print(f"  [{'OK' if ok else 'FAIL'}] 期望 cmd={expect_cmd}, code=0")
    if not ok:
        raise SystemExit(f"步骤 [{step}] 校验失败")
    return robj


def main():
    ap = argparse.ArgumentParser(description="Echo P1 链路冒烟")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=9001)
    args = ap.parse_args()

    open_id = f"smoke-{int(time.time())}"
    print(f"连接 ws://{args.host}:{args.port}/  openId={open_id}")
    ws = WsClient(args.host, args.port)

    try:
        # 1) 登录 1001 -> 1002
        r = call(ws, "登录", 1001,
                 {"openId": open_id, "clientVersion": "smoke-1.0"}, 1002)
        account_id = r.get("accountId")
        print(f"     >> accountId={account_id}, newAccount={r.get('newAccount')}")

        # 2) 提交偏好 1201 -> 1202（触发 LLM 补全 mock + 向量生成 + pgvector 落库）
        r = call(ws, "提交偏好", 1201,
                 {"rawPrefs": ["登山", "赛博朋克", "古典乐", "独居观影", "手冲咖啡"]}, 1202)
        print(f"     >> profileId={r.get('profileId')}, vectorId={r.get('vectorId')}")

        # 3) 进入空间 1301 -> 1302
        r = call(ws, "进入空间", 1301, {}, 1302)
        space_id = r.get("spaceId")
        print(f"     >> spaceId={space_id}, presetSetId={r.get('presetSetId')}, "
              f"hostConfig={r.get('hostConfig')}")

        # 4) 查共鸣 1401 -> 1402（单账号下候选可能为空，链路本身打通即可）
        r = call(ws, "查共鸣", 1401, {"topN": 5, "threshold": 2.0}, 1402)
        print(f"     >> candidates={r.get('candidates', [])}")

        # 5) 留痕 1503 -> 1504（在自己空间留下一条回声）
        trace_payload = json.dumps({"type": "gesture", "emoji": "🌧️", "note": "路过留痕"},
                                   ensure_ascii=False)
        r = call(ws, "留痕", 1503,
                 {"ownerSpaceId": str(space_id), "payload": trace_payload,
                  "ttlMillis": str(10 * 60 * 1000)}, 1504)
        echo_id = r.get("echoId")
        print(f"     >> echoId={echo_id}, expireAt={r.get('expireAt')}")

        # 6) 拉回声 1501 -> 1502（应能看到刚留下的回声）
        r = call(ws, "拉回声", 1501, {"ownerSpaceId": str(space_id)}, 1502)
        echoes = r.get("echoes", [])
        print(f"     >> 回声条数={len(echoes)}")
        for e in echoes:
            print(f"        - echoId={e.get('echoId')} from={e.get('fromAccountId')} "
                  f"payload={e.get('payload')}")

        # 7) 心跳 9001 -> 9002
        r = call(ws, "心跳", 9001, {"clientTime": str(int(time.time() * 1000))}, 9002)
        print(f"     >> serverTime={r.get('serverTime')}")

        print("\n========== 冒烟全部通过：P1 核心链路连通 ==========")
    finally:
        ws.close()


if __name__ == "__main__":
    main()
