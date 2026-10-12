#!/usr/bin/env python3
"""G5 词表重载链路加固 E2E 核查（12 章 v2.112/v2.113/v2.114，清单 G5 段）

覆盖可远程自动化的四步（**须先部署含 G5 三批改动的构建**）：

  步骤 1  回环消除前的基线读数（reload.succeeded 现值）
  步骤 1'  POST /reload 一次 → 计数应 +1（单条触发的净装载数：本地一次；回环已跳过）
  步骤 3  外部裸信号（publish_reload_signal.py / redis-cli）→ 计数应 **再 +1**（外部通道不受自跳板影响）
  步骤 6  指标名：应出现 rag_guardrail_rule_create_total，且旧名 rag_guardrail_rule_total 消失

不在此脚本内（需本机 psql/JDBC 或服务端日志/重启，脚本会打印照做指令）：

  步骤 2  判别点：写路径单次 PUT/POST/DELETE 后**日志只应出现一行**「护栏词表热重载成功」
         （旧形态两行：本地一次 + 自身回环一次；指标 +1 两种形态相同，日志才是判别点）
  步骤 4  DB 源丢信号兜底：直接改库不发信号 → ≤轮询间隔（缺省 60s）内 drill 应看到新动作
         （修订号取内容指纹 ⇒ 不改 updated_at 的直改 SQL 同样被捕获，G5 本地 E2E 已实证；
          旧的时间戳型形态对该路径无感，勿回退）
  步骤 5  file 回退态写路径 fail-loud：以 RAG_GUARDRAIL_RULES_SOURCE=file 重启 → POST/PUT/DELETE /rules 应 400
         （错误码 GUARDRAIL_RULE_SOURCE_READONLY 按全仓异常约定只落服务日志，响应体只有 code=400 + message）
         （核验后改回 db 重启）

用法：python3 tools/guardrail/g5_e2e_check.py [--env-file ~/.kb-e2e.env]
环境变量：APP_API_DOMAIN、ADMIN_JWT（步骤 3 另需 REDIS_HOST/REDIS_PORT/REDIS_PASSWORD）
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
SUCCEEDED = "rag_guardrail_reload_succeeded_total"
FAILED = "rag_guardrail_reload_failed_total"
NEW_RULE_METRIC = "rag_guardrail_rule_create_total"
OLD_RULE_METRIC = "rag_guardrail_rule_total"

RESULTS: list[tuple[str, str, str]] = []


def load_env(path: str) -> None:
    env_file = Path(path).expanduser()
    if not env_file.exists():
        return
    for line in env_file.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


def http(url: str, method: str = "GET", timeout: int = 30) -> str:
    req = urllib.request.Request(url, method=method)
    token = os.environ.get("ADMIN_JWT", "")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8", "replace")


def metric(name: str) -> float | None:
    """取指定指标现值（无则 None）"""
    scrape = http(os.environ["APP_API_DOMAIN"].rstrip("/") + "/actuator/prometheus")
    for line in scrape.splitlines():
        if line.startswith(name + "{") or line.startswith(name + " "):
            return float(line.rsplit(" ", 1)[1])
    return None


def record(step: str, verdict: str, detail: str) -> None:
    RESULTS.append((step, verdict, detail))
    print(f"[{verdict}] {step} —— {detail}")


def step_reload_once() -> None:
    domain = os.environ["APP_API_DOMAIN"].rstrip("/")
    before = metric(SUCCEEDED)
    if before is None:
        record("步骤 1", "FAIL", f"抓取面缺 {SUCCEEDED}（端点不可达？）")
        return
    record("步骤 1", "INFO", f"基线 {SUCCEEDED} = {before:.0f}，{FAILED} = {metric(FAILED)}")

    body = http(domain + "/api/v1/admin/guardrail/reload", method="POST")
    data = json.loads(body).get("data", {})
    record("步骤 1'", "INFO",
           f"POST /reload → {json.dumps(data, ensure_ascii=False)}")

    after = metric(SUCCEEDED)
    delta = (after or 0) - before
    verdict = "PASS" if delta == 1 else "FAIL"
    record("步骤 1'", verdict,
           f"计数 {before:.0f} → {after:.0f}（Δ={delta:+.0f}，期望 +1）。注意：新旧两种形态此处均 +1"
           "（旧形态计的是回环那次）——**判别点是步骤 6 的构建身份与步骤 2 的日志行数**")


def step_external_signal() -> None:
    domain = os.environ["APP_API_DOMAIN"].rstrip("/")
    before = metric(SUCCEEDED)
    publisher = HERE / "publish_reload_signal.py"
    if not publisher.exists():
        record("步骤 3", "SKIP", "未找到 publish_reload_signal.py")
        return
    try:
        out = subprocess.run([sys.executable, str(publisher)], capture_output=True,
                             text=True, timeout=60)
    except Exception as exc:  # noqa: BLE001
        record("步骤 3", "FAIL", f"外部信号发布异常：{exc}")
        return
    if out.returncode != 0:
        record("步骤 3", "FAIL", (out.stderr or out.stdout).strip()[:200])
        return
    line = out.stdout.strip().splitlines()[-1] if out.stdout.strip() else ""
    import time
    time.sleep(3)
    after = metric(SUCCEEDED)
    delta = (after or 0) - (before or 0)
    verdict = "PASS" if delta == 1 else "FAIL"
    record("步骤 3", verdict,
           f"外部裸载荷 → 计数 {before:.0f} → {after:.0f}（Δ={delta:+.0f}，期望 +1；{line}）")


def step_metric_name() -> None:
    domain = os.environ["APP_API_DOMAIN"].rstrip("/")
    scrape = http(domain + "/actuator/prometheus")
    has_new = re.search(rf"^{NEW_RULE_METRIC}\b", scrape, re.M) is not None
    has_old = re.search(rf"^{OLD_RULE_METRIC}\b", scrape, re.M) is not None
    if has_new and not has_old:
        record("步骤 6", "PASS", f"{NEW_RULE_METRIC} 在册且旧名 {OLD_RULE_METRIC} 已消失")
    else:
        record("步骤 6", "FAIL",
               f"{NEW_RULE_METRIC} 在册={has_new}，旧名 {OLD_RULE_METRIC} 在册={has_old}")
        print("  ⚠ 当前实例仍是 G5 之前构建——步骤 2/4/5 在新构建重启前无意义，"
              "请重建镜像并重启后重跑本脚本")


def print_manual_steps() -> None:
    print("\n── 需人工执行的三步（脚本不可远程完成） ──")
    print("步骤 2（判别点：日志）: 做一次 no-op PUT /api/v1/admin/guardrail/rules/<id>")
    print("        期望：服务日志只出现一行「护栏词表热重载成功」（旧形态两行 = 本地 + 自身回环）")
    print("步骤 4（DB 源丢信号兜底）: 直接改库且**不发信号**，≤ 轮询间隔内应自动收敛")
    print("        UPDATE kb_guardrail_rule SET action='FLAG' WHERE id='<某 BLOCK 词项>';")
    print("        等 rag.guardrail.reload.poll-interval-seconds（缺省 60s）→")
    print("        POST /api/v1/admin/guardrail/drill 该词项文本 → 动作应翻为 FLAG，日志含「修订号变更」；核验后改回 BLOCK")
    print("步骤 5（file 回退态 fail-loud）: 以 RAG_GUARDRAIL_RULES_SOURCE=file 重启 →")
    print("        POST /api/v1/admin/guardrail/rules 应 400 GUARDRAIL_RULE_SOURCE_READONLY")
    print("        （只读列表/演练与 POST /reload 仍可用）；核验后改回 db 重启")


def main() -> int:
    parser = argparse.ArgumentParser(description="G5 词表重载链路加固 E2E 核查")
    parser.add_argument("--env-file", default="~/.kb-e2e.env")
    parser.add_argument("--skip-external", action="store_true", help="跳过步骤 3（无 Redis 凭据时）")
    args = parser.parse_args()

    load_env(args.env_file)
    if not os.environ.get("APP_API_DOMAIN"):
        print("缺少 APP_API_DOMAIN（--env-file 或环境变量）", file=sys.stderr)
        return 2

    step_reload_once()
    if not args.skip_external:
        step_external_signal()
    step_metric_name()
    print_manual_steps()

    failed = [r for r in RESULTS if r[1] == "FAIL"]
    print(f"\n汇总：{len(RESULTS)} 项，FAIL {len(failed)}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
