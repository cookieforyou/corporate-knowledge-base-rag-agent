#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""护栏词表热重载信号发布（安全簇⑥ F1，Git Ops 运营通道）

词表文件经 import_words.py 带外更新 + git 同步至运行环境外部路径后，
发布 Redis pub/sub 信号触发在线实例免重启重载（GuardrailReloadCoordinator
频道 rag:guardrail:reload）；无信号时 file: 源 mtime 轮询（默认 60s）兜底。

用法：
    python3 publish_reload_signal.py

连接参数经环境变量（与 application-infra.yml 同源命名）：
    REDIS_HOST（默认 localhost）/ REDIS_PORT（默认 6379）/ REDIS_PASSWORD（默认空）

发布通道优先级（逐级回落，运营机零依赖兜底）：
    1. redis-py（若已安装）；
    2. redis-cli（若 PATH 存在，which 前置探测防二进制缺失穿透）;
    3. 纯标准库 RESP socket（零三方依赖，Python 3 + TCP 可达即可）。

stdout 纪律：仅回显发布结果与订阅者数量，不涉词面。
"""

import os
import shutil
import socket
import subprocess
import sys

CHANNEL = "rag:guardrail:reload"
MESSAGE = "reload"


def publish_via_redis_py(host: str, port: int, password: str) -> int:
    import redis  # 延迟导入：无 redis-py 时回落后续通道

    client = redis.Redis(host=host, port=port, password=password or None, socket_timeout=5)
    return int(client.publish(CHANNEL, MESSAGE))


def publish_via_redis_cli(host: str, port: int, password: str) -> int:
    if shutil.which("redis-cli") is None:
        raise ImportError("redis-cli 不在 PATH")  # 与 redis-py 缺失同语义回落
    cmd = ["redis-cli", "-h", host, "-p", str(port)]
    if password:
        cmd += ["-a", password, "--no-auth-warning"]
    cmd += ["PUBLISH", CHANNEL, MESSAGE]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "redis-cli 执行失败")
    return int(result.stdout.strip())


def publish_via_raw_resp(host: str, port: int, password: str) -> int:
    """纯标准库 RESP 发布——AUTH + PUBLISH 两条命令，只解析简单串/整数应答。"""

    def encode_command(*args: str) -> bytes:
        parts = [f"*{len(args)}\r\n".encode("ascii")]
        for arg in args:
            raw = arg.encode("utf-8")
            parts.append(b"$" + str(len(raw)).encode("ascii") + b"\r\n" + raw + b"\r\n")
        return b"".join(parts)

    def read_reply(sock: socket.socket):
        line = b""
        while not line.endswith(b"\r\n"):
            chunk = sock.recv(1)
            if not chunk:
                raise RuntimeError("连接被服务端关闭")
            line += chunk
        prefix, payload = line[:1], line[1:-2]
        if prefix == b"-":
            raise RuntimeError(payload.decode("utf-8", "replace"))
        if prefix == b"+":
            return payload.decode("utf-8", "replace")
        if prefix == b":":
            return int(payload)
        raise RuntimeError(f"非预期 RESP 应答类型: {prefix!r}")

    with socket.create_connection((host, port), timeout=5) as sock:
        if password:
            sock.sendall(encode_command("AUTH", password))
            if read_reply(sock) != "OK":
                raise RuntimeError("AUTH 未确认")
        sock.sendall(encode_command("PUBLISH", CHANNEL, MESSAGE))
        receivers = read_reply(sock)
        if not isinstance(receivers, int):
            raise RuntimeError(f"PUBLISH 应答非整数: {receivers!r}")
        return receivers


def main() -> int:
    host = os.environ.get("REDIS_HOST", "localhost")
    port = int(os.environ.get("REDIS_PORT", "6379"))
    password = os.environ.get("REDIS_PASSWORD", "")
    transports = (
        ("redis-py", publish_via_redis_py),
        ("redis-cli", publish_via_redis_cli),
        ("raw-resp", publish_via_raw_resp),
    )
    receivers = None
    used = ""
    last_error: Exception | None = None
    for name, transport in transports:
        try:
            receivers = transport(host, port, password)
            used = name
            break
        except ImportError as exc:
            last_error = exc  # 通道本身不可用，静默回落下一通道
        except Exception as exc:  # noqa: BLE001——运营脚本统一错误出口
            last_error = exc
            print(f"[publish_reload_signal] 通道 {name} 失败: {exc}", file=sys.stderr)
    if receivers is None:
        print(f"[publish_reload_signal] 发布失败（{host}:{port}）: 全部通道不可用，最后错误: {last_error}",
              file=sys.stderr)
        print("[publish_reload_signal] 提示：无信号时 file: 源 mtime 轮询（默认 60s）仍会兜底重载", file=sys.stderr)
        return 1
    print(f"[publish_reload_signal] 信号已发布: 频道={CHANNEL} 通道={used} 订阅者={receivers}")
    if receivers == 0:
        print("[publish_reload_signal] 警告：当前无订阅者（实例未启动 / reload 开关关闭 / Redis 非同源）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
