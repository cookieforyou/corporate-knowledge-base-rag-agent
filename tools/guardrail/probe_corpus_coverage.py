#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""语料护栏词表覆盖批量复算探针（安全簇⑥ G2 开篇，内容盲形态）

用途：不启动服务，以与运行时等价的语义（复用 probe_match.py 同源管线：
零宽剥离→NFKC→空白折叠；KEYWORD 小写子串 / REGEX 预编译 find）对语料集
逐条复算词表命中，输出四分区计数——

  BLOCK    : 命中任一 BLOCK 档词项 → 运行时直拦（L1 防域）
  KEYWORD  : 无 BLOCK、命中 KEYWORD 档词项 → L1 已观察，L2 不触发（干词压制）
  TRIGGER  : 无 BLOCK 无 KEYWORD、命中 REGEX 档词项 → L2 可触发
             （与 SemanticInjectionAdvisor 触发条件「REGEX 命中 ∧ 干词未命中」同语义）
  SILENT   : 全档零命中（触发面静默区）

即 v2.48 触发面全景划分（12 章口径修正批）的批量复算形态。

语料形态兼容：
  - injection-qa.json 编码引用形态（question Base64 + questionEncoding）自动解码；
  - 干净集明文 question 形态（boundary-qa/negative-out-of-kb/各域 QA）直接消费。

输出纪律（§7 条 1/4）：只回显用例 ID / 命中词项 ID / 族系 / 计数——
绝不回显问句原文与词项值。

用法：
  # 注入语料基线对账（bundled 基线词表）
  python3 tools/guardrail/probe_corpus_coverage.py \\
      --corpus kb-eval/src/main/resources/golden/injection-qa.json
  # 干净集 FLAG 误伤观察（BLOCK 零命中为门禁口径，FLAG 命中列 ID 供运营判断）
  python3 tools/guardrail/probe_corpus_coverage.py \\
      --corpus kb-eval/src/main/resources/golden/boundary-qa.json
  # 复算生产覆盖形态（外部词表 + CSV 兼容源）
  python3 tools/guardrail/probe_corpus_coverage.py \\
      --corpus <path> --rules file:/opt/kb/injection-rules.yml --csv "a,b"
"""

import argparse
import base64
import json
import sys
from pathlib import Path

import probe_match as pm  # 同源管线复用（同目录装载）


def decode_question(item: dict) -> str:
    question = item.get("question", "")
    encoding = (item.get("questionEncoding") or "").strip().upper()
    if encoding == "BASE64":
        return base64.b64decode(question, validate=True).decode("utf-8")
    return question


def classify(hits) -> str:
    if any(r["action"] == "BLOCK" for r in hits):
        return "BLOCK"
    if any(r["type"] == "KEYWORD" for r in hits):
        return "KEYWORD"
    if hits:
        return "TRIGGER"
    return "SILENT"


def main() -> int:
    ap = argparse.ArgumentParser(description="语料词表覆盖批量复算（内容盲：只回显 ID/计数/族系）")
    ap.add_argument("--corpus", required=True, help="语料 JSON 路径（list[用例]，golden 目录形态）")
    ap.add_argument("--side", choices=["injection", "output"], default="injection")
    ap.add_argument("--rules", help="结构化词表路径（镜像 rag.guardrail.rules.*-location；缺省 bundled 基线）")
    ap.add_argument("--csv", default="", help="CSV 兼容源（镜像运维侧带外扩充键）")
    args = ap.parse_args()

    corpus_path = Path(args.corpus)
    if not corpus_path.exists():
        print(f"语料文件不存在: {corpus_path}")
        return 2
    cases = json.loads(corpus_path.read_text(encoding="utf-8"))
    if not isinstance(cases, list):
        print("语料文件须为用例数组（golden 目录形态）")
        return 2

    rules_path = Path(args.rules) if args.rules else pm.DEFAULT_RULES[args.side]
    if args.rules and not rules_path.exists():
        print(f"结构化词表不存在（运行时将回落内置缺省）: {rules_path}")
        rules_path = pm.DEFAULT_RULES[args.side]
    merged = {}
    for rule in pm.load_csv_compat(args.csv):
        merged[rule["id"]] = rule
    for rule in pm.load_structured(rules_path):
        merged[rule["id"]] = rule
    rules = list(merged.values())
    blocks = sum(1 for r in rules if r["action"] == "BLOCK")
    print(f"词表装载: {len(rules)} 条（BLOCK {blocks} / FLAG {len(rules) - blocks}）源={rules_path.name}")
    print(f"语料装载: {len(cases)} 条，源={corpus_path.name}")

    totals = {"BLOCK": 0, "KEYWORD": 0, "TRIGGER": 0, "SILENT": 0}
    groups = {}
    for item in cases:
        case_id = item.get("id", "?")
        group = item.get("attackType") or item.get("category") or "?"
        try:
            view = pm.normalize(decode_question(item))
        except Exception as exc:  # noqa: BLE001——单条解码失败不阻断批量
            print(f"  WARN {case_id} 解码失败跳过: {exc.__class__.__name__}")
            continue
        hits = [r for r in rules if pm.match(r, view)]
        cls = classify(hits)
        totals[cls] += 1
        buckets = groups.setdefault(group, {"BLOCK": 0, "KEYWORD": 0, "TRIGGER": 0, "SILENT": 0})
        buckets[cls] += 1
        if cls != "SILENT":
            hit_ids = ",".join(r["id"] for r in hits)
            families = sorted({r["family"] for r in hits})
            print(f"  {case_id} → {cls}（命中 {hit_ids}，族系 {families}）")

    print("── 四分区汇总 ──")
    print(f"BLOCK 直拦 {totals['BLOCK']} / KEYWORD 压制 {totals['KEYWORD']} / "
          f"TRIGGER 可触发 {totals['TRIGGER']} / SILENT 静默 {totals['SILENT']}（合计 {sum(totals.values())}）")
    print("── 分组分解（attackType/category）──")
    for group in sorted(groups):
        b = groups[group]
        print(f"  {group}: BLOCK {b['BLOCK']} / KEYWORD {b['KEYWORD']} / "
              f"TRIGGER {b['TRIGGER']} / SILENT {b['SILENT']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
