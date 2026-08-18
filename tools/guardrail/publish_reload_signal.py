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

stdout 纪律：仅回显发布结果与订阅者数量，不涉词面。
"""

import os
import subprocess
import sys

CHANNEL = "rag:guardrail:reload"


def publish_via_redis_py(host: str, port: int, password: str) -> int:
    import redis  # 延迟导入：无 redis-py 时回落 redis-cli

    client = redis.Redis(host=host, port=port, password=password or None, socket_timeout=5)
    return int(client.publish(CHANNEL, "reload"))


def publish_via_redis_cli(host: str, port: int, password: str) -> int:
    cmd = ["redis-cli", "-h", host, "-p", str(port)]
    if password:
        cmd += ["-a", password, "--no-auth-warning"]
    cmd += ["PUBLISH", CHANNEL, "reload"]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "redis-cli 执行失败")
    return int(result.stdout.strip())


def main() -> int:
    host = os.environ.get("REDIS_HOST", "localhost")
    port = int(os.environ.get("REDIS_PORT", "6379"))
    password = os.environ.get("REDIS_PASSWORD", "")
    try:
        try:
            receivers = publish_via_redis_py(host, port, password)
        except ImportError:
            receivers = publish_via_redis_cli(host, port, password)
    except Exception as exc:  # noqa: BLE001——运营脚本统一错误出口
        print(f"[publish_reload_signal] 发布失败（{host}:{port}）: {exc}", file=sys.stderr)
        print("[publish_reload_signal] 提示：无信号时 file: 源 mtime 轮询（默认 60s）仍会兜底重载", file=sys.stderr)
        return 1
    print(f"[publish_reload_signal] 信号已发布: 频道={CHANNEL} 订阅者={receivers}")
    if receivers == 0:
        print("[publish_reload_signal] 警告：当前无订阅者（实例未启动 / reload 开关关闭 / Redis 非同源）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
