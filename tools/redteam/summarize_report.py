#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Promptfoo red-team 报告摘要器（安全簇⑥ G1，内容盲形态）

解析 `promptfoo redteam run -o <output.json>` 的输出 JSON，只回聚合计数：
  - 总体：防御成功 / 突破 / 错误 三桶
  - 逐 plugin / 逐 strategy 分解
  - --out-md 生成归档 markdown（同口径聚合数，供 docs/project-progress/ 入档）

判定口径：promptfoo redteam 中 result.success=true 表示输出未顺从攻击意图
（防御成功——拦截/拒答/无害应答）；success=false 表示裁判认定顺从（突破）；
provider 层 error（多为护栏 400 拒绝或网络异常）单列 error 桶，首跑后
结合服务端审计（kb_audit_log REJECTED 计数）归因。

输出纪律（§7 条 1/4）：绝不回显 prompt/response/description/reason 等
任何字面内容——聚合维度仅 plugin/strategy 枚举名与计数。

用法：
  python3 tools/redteam/summarize_report.py --output redteam-result.json
  python3 tools/redteam/summarize_report.py --output redteam-result.json \\
      --out-md docs/project-progress/redteam-report-g1-<label>.md
"""

import argparse
import json
import sys
from pathlib import Path


def iter_results(data):
    """兼容 promptfoo 输出形态：{results:{results:[...]}} / {results:[...]} / [...]"""
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        results = data.get("results", data)
        if isinstance(results, list):
            return results
        if isinstance(results, dict):
            inner = results.get("results", [])
            return inner if isinstance(inner, list) else []
    return []


def classify(record) -> str:
    if record.get("error"):
        return "error"
    success = record.get("success")
    if success is None:
        grading = record.get("gradingResult") or {}
        success = grading.get("pass")
    if success is None:
        return "error"
    return "defended" if success else "breached"


def dims(record):
    meta = (record.get("testCase") or {}).get("metadata") or record.get("metadata") or {}
    plugin = meta.get("pluginId") or meta.get("plugin") or "?"
    strategy = meta.get("strategyId") or meta.get("strategy") or "(base)"
    return plugin, strategy


def bump(table, key, bucket):
    row = table.setdefault(key, {"defended": 0, "breached": 0, "error": 0})
    row[bucket] += 1


def main() -> int:
    ap = argparse.ArgumentParser(description="Promptfoo 报告摘要（内容盲：只回聚合数）")
    ap.add_argument("--output", required=True, help="promptfoo -o 输出 JSON 路径")
    ap.add_argument("--out-md", help="可选：聚合摘要 markdown 归档路径")
    ap.add_argument("--label", default="", help="归档标签（写入 md 标题，不涉内容）")
    args = ap.parse_args()

    path = Path(args.output)
    if not path.exists():
        print(f"输出文件不存在: {path}")
        return 2
    data = json.loads(path.read_text(encoding="utf-8"))
    records = iter_results(data)
    if not records:
        print("未解析到结果记录（输出形态不识别或为空）")
        return 1

    totals = {"defended": 0, "breached": 0, "error": 0}
    by_plugin, by_strategy = {}, {}
    for record in records:
        if not isinstance(record, dict):
            continue
        bucket = classify(record)
        totals[bucket] += 1
        plugin, strategy = dims(record)
        bump(by_plugin, plugin, bucket)
        bump(by_strategy, strategy, bucket)

    total = sum(totals.values())
    lines = []
    lines.append(f"# Promptfoo red-team 摘要{('（' + args.label + '）') if args.label else ''}")
    lines.append("")
    lines.append(f"- 样本总数：{total}")
    lines.append(f"- 防御成功（拦截/拒答/未顺从）：{totals['defended']}")
    lines.append(f"- 突破（裁判认定顺从）：{totals['breached']}")
    lines.append(f"- error（provider 层异常，多为护栏 400 拒绝）：{totals['error']}")
    for title, table in (("## 逐 plugin 分解", by_plugin), ("## 逐 strategy 分解", by_strategy)):
        lines.append("")
        lines.append(title)
        lines.append("")
        lines.append("| 维度 | 防御成功 | 突破 | error | 合计 |")
        lines.append("| --- | ---: | ---: | ---: | ---: |")
        for key in sorted(table):
            row = table[key]
            n = sum(row.values())
            lines.append(f"| {key} | {row['defended']} | {row['breached']} | {row['error']} | {n} |")

    text = "\n".join(lines)
    print(text)
    if args.out_md:
        out = Path(args.out_md)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text + "\n", encoding="utf-8")
        print(f"\n[summarize_report] 摘要已归档: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
