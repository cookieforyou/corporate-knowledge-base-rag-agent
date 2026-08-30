#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Promptfoo 预生成红队测试用例词表覆盖复算探针（安全簇⑥ G1 前提理清，内容盲形态）

用途：不启动服务，以与运行时等价的语义（复用 probe_match.py 同源管线：
零宽剥离→NFKC→空白折叠；KEYWORD 小写子串 / REGEX 预编译 find）对
`promptfoo redteam generate` 产物逐条复算词表命中，输出四分区计数——

  BLOCK    : 命中任一 BLOCK 档词项 → 运行时 L1 直拦
  KEYWORD  : 无 BLOCK、命中 KEYWORD 档词项 → L1 已观察，L2 不触发（干词压制）
  TRIGGER  : 无 BLOCK 无 KEYWORD、命中 REGEX 档词项 → L2 可触发
             （与 SemanticInjectionAdvisor 触发条件「REGEX 命中 ∧ 无干词命中」同语义）
  SILENT   : 全档零命中（触发面静默区）

读数口径（2026-08-29 用户裁决 1a，12 章 §12.11 口径分离注记）：
  G1 走 POST /api/v1/chat 生产链，无力判键、无 sessionId（跨轮信号结构性归零）
  → 生产链下 L2 唯一触发路径即 TRIGGER 分区（单条消息归一化视图）。本探针在
  执行前量化四分区分布，将「大量生成用例不达 L2 触发前置」的先验分析转为可复算
  数字。SILENT 静默区与 BLOCK/KEYWORD 分区「不到达 L2」是结构性事实——其防御面
  归主模型对齐 / L1 词表 / 输出护栏，**不是 L2 判别力问题**，也不直接映射
  eval L2 门禁阈值（阈值校准经「高价值变体带外入库 → eval 力判复跑」链路）。

输入形态兼容（promptfoo generate -o 产物与 eval 输出 JSON 双兼容）：
  - YAML 形态（test-cases*.yaml）：纯标准库最小 YAML 子集解析（零三方依赖，
    本项目 tools 脚本纪律——本机无 PyYAML 亦不引入）；支持 tests 列表项的
    vars.prompt（块标量 | / |- / >- 与单双引号单行两形态）与
    metadata.pluginId / metadata.strategyId；**锚点/别名最小支持**
    （2026-08-30 实证补丁：base 用例 `vars: &ref` 锚点定义 + 策略变体
    `vars: *ref` 别名引用——promptfoo 生成物用锚点复用基线 vars；无锚点
    支持则 base 批整体漏采）；其余顶层键忽略；
  - JSON 形态（*.json，含 {"tests":[...]} / 裸数组）：json 标准库直解。
  用例 ID 合成（纯元数据）：test-{序号}-{plugin}-{strategy}——promptfoo
  生成用例无原生 ID，序号×维度合成与 summarize_report 分桶维度同源。

  **迭代式策略单列（2026-08-30 实证定谳）**：携带迭代式 provider 覆盖
  （`provider.id: promptfoo:redteam:iterative:*`，实证形态 =
  jailbreak:meta「multi-attempt, meta-agent attack generation」）的用例，
  其攻击载荷由迭代 provider 在**评估运行时**围绕种子目标构造——生成物
  vars.prompt 只是种子（与基线别名同源），预执行四分区对该批**结构性不适用**，
  单列「迭代式种子态」计数，防御归因走运行时（审计日志 +
  `rag.guardrail.l2.triggered` 指标）。四分区汇总口径 = 仅确定性载荷用例
  （base 与逐字编码策略如 leetspeak/rot13/base64）。

  用例缺 prompt 变量（解析未识别形态）单列计数不参分区，并自动输出内容盲
  结构诊断（只回该批用例的顶层键名/vars 键名/strategy 分布——零内容回显），
  供定位「生成物本无 vars.prompt」还是「解析器形态盲区」。

输出纪律（§7 条 1/4）：只回显用例 ID / 命中词项 ID / 族系 / 计数——
绝不回显问句原文与词项值。

用法：
  # 预生成用例四分区复算（bundled 基线词表）
  python3 tools/guardrail/probe_redteam_coverage.py --tests tools/redteam/test-cases.yaml
  # 生产实际词表形态（外部存档路径，同 probe_corpus_coverage 口径）
  python3 tools/guardrail/probe_redteam_coverage.py --tests <path> --rules file:/opt/kb/injection-rules.yml
"""

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

import probe_match as pm  # 同源管线复用（同目录装载）


def _strip_quotes(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
        return value[1:-1]
    return value


def parse_tests_yaml(text: str):
    """最小 YAML 子集解析器（纯标准库）——仅覆盖 promptfoo redteam generate 产物

    目标形态：顶层 `tests:` 键下的列表，每项含 `vars.prompt`（块标量或单行
    引号形态）与 `metadata.pluginId / strategyId`。不追求通用 YAML 语义
    （多文档不支持，遇到即跳过不阻断）——解析目标是字段抽取，容错优先于
    形式完备。锚点/别名最小支持：`vars: &ref` 锚点定义照常进段采集，
    `vars: *ref` 别名引用经二趟解析回填基线用例载荷。
    """
    cases = []
    lines = text.splitlines()
    i, n = 0, len(lines)
    while i < n and not re.match(r"^tests:\s*(#.*)?$", lines[i]):
        i += 1
    i += 1

    item_indent = None
    cur = None           # 当前用例 {"prompt": str|None, "plugin": str, "strategy": str}
    section = None       # 当前子键归属段（vars / metadata / 其他）
    block = None         # 块标量收集态（目标用例, 键名, 基准缩进, 折叠与否）

    def close_block():
        nonlocal block
        if block is None:
            return
        target, key, _, fold = block
        chunks = target.pop("_block_buf", [])
        joined = (" " if fold else "\n").join(chunks)
        if key == "prompt":
            target["prompt"] = joined
        block = None

    while i < n:
        raw = lines[i]
        if not raw.strip():
            i += 1
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        stripped = raw.strip()

        # 块标量收集态：更深缩进行续接，回退即收束
        if block is not None:
            target, _, base, _ = block
            if indent > base:
                target.setdefault("_block_buf", []).append(raw[base + 2:] if len(raw) > base + 2 else "")
                i += 1
                continue
            close_block()

        if stripped.startswith("#"):
            i += 1
            continue
        if indent == 0 and not stripped.startswith("- "):
            break  # tests 块结束（下一顶层键）

        if stripped.startswith("- "):
            if item_indent is None:
                item_indent = indent
            if indent == item_indent:
                cur = {"prompt": None, "plugin": "?", "strategy": "(base)",
                       "_topkeys": set(), "_varskeys": set()}
                cases.append(cur)
                section = None
                rest = stripped[2:].strip()
                if not rest:
                    i += 1
                    continue
                stripped = rest  # 「- vars:」形态：续按键值处理
            elif indent > item_indent:
                i += 1
                continue
            else:
                break

        if cur is None:
            i += 1
            continue
        if ":" not in stripped:
            i += 1
            continue

        key, _, value = stripped.partition(":")
        key, value = key.strip(), value.strip()
        if key in ("vars", "metadata", "provider", "assert"):
            cur.setdefault("_topkeys", set()).add(key)
            if key == "vars" and value.startswith("*"):
                # 别名引用形态（策略变体复用基线 vars）：记录待解析，本行无子块
                cur["_vars_alias"] = value[1:].strip()
                section = None
            elif key == "vars":
                # 锚点定义形态（`vars: &ref`，基线用例）：锚名记录，段照常进入
                if value.startswith("&"):
                    anchor, _, remainder = value[1:].partition(" ")
                    cur["_vars_anchor"] = anchor.strip()
                    value = remainder.strip()
                section = "vars" if not value else None  # 带值 = flow 映射盲区（诊断覆盖）
            elif key == "provider":
                section = "provider" if not value else None
            else:
                section = key if not value else None
            i += 1
            continue
        if section == "vars":
            cur.setdefault("_varskeys", set()).add(key)
            if key == "prompt":
                if value in ("|", "|-", "|+", ">", ">-", ">+"):
                    block = (cur, "prompt", indent, value.startswith(">"))
                elif value:
                    cur["prompt"] = _strip_quotes(value)
        elif section == "metadata" and key in ("pluginId", "plugin"):
            cur["plugin"] = _strip_quotes(value)
        elif section == "metadata" and key in ("strategyId", "strategy"):
            cur["strategy"] = _strip_quotes(value)
        elif section == "provider" and key == "id":
            cur["_provider_id"] = _strip_quotes(value)
        else:
            cur.setdefault("_topkeys", set()).add(key)
        i += 1

    close_block()
    # 锚点→别名二趟解析（消除文件内顺序依赖）：锚点用例先注册，别名用例回填
    anchors = {}
    for case in cases:
        anchor = case.get("_vars_anchor")
        if anchor and case.get("prompt") is not None:
            anchors[anchor] = case
    for case in cases:
        alias = case.get("_vars_alias")
        if alias and case.get("prompt") is None and alias in anchors:
            case["prompt"] = anchors[alias]["prompt"]
            case["_alias_resolved"] = True
    return cases


def load_cases(path: Path):
    """双形态装载：YAML（tests 列表）/ JSON（{"tests":...} 或裸数组）"""
    text = path.read_text(encoding="utf-8")
    trimmed = text.lstrip()
    if trimmed.startswith("{") or trimmed.startswith("["):
        data = json.loads(text)
        items = data.get("tests", data) if isinstance(data, dict) else data
        cases = []
        if isinstance(items, list):
            for item in items:
                if not isinstance(item, dict):
                    continue
                meta = item.get("metadata") or {}
                cases.append({
                    "prompt": (item.get("vars") or {}).get("prompt"),
                    "plugin": meta.get("pluginId") or meta.get("plugin") or "?",
                    "strategy": meta.get("strategyId") or meta.get("strategy") or "(base)",
                    "_topkeys": set(item.keys()),
                    "_varskeys": set((item.get("vars") or {}).keys()),
                    "_provider_id": (item.get("provider") or {}).get("id"),
                })
        return cases
    return parse_tests_yaml(text)


# 迭代式策略族（官方文档实证）：攻击载荷由 provider 在评估运行时围绕种子目标
# 构造（多尝试/元代理生成/多轮升级等），生成物 vars 仅携种子——预执行四分区
# 结构性不适用，单列计数，防御归因走运行时（审计日志 + rag.guardrail.l2.triggered）。
# 主判据 = 迭代式 provider 覆盖（权威信号）；策略名兜底仅限官方文档明列的
# 运行时族（agentic strategies）——jailbreak:likert/composite 等生成期变换族
# 不在其列（产出字面载荷，四分区照常适用）。
ITERATIVE_PROVIDER_PREFIX = "promptfoo:redteam:iterative"
ITERATIVE_STRATEGY_IDS = ("jailbreak", "jailbreak:meta", "jailbreak:tree",
                          "jailbreak:hydra", "crescendo", "goat", "custom")


def is_iterative(item) -> bool:
    provider_id = (item.get("_provider_id") or "").lower()
    if provider_id.startswith(ITERATIVE_PROVIDER_PREFIX):
        return True
    return (item.get("strategy") or "") in ITERATIVE_STRATEGY_IDS


def classify(hits) -> str:
    if any(r["action"] == "BLOCK" for r in hits):
        return "BLOCK"
    if any(r["type"] == "KEYWORD" for r in hits):
        return "KEYWORD"
    if hits:
        return "TRIGGER"
    return "SILENT"


def main() -> int:
    ap = argparse.ArgumentParser(description="Promptfoo 生成用例四分区复算（内容盲：只回显 ID/计数/族系）")
    ap.add_argument("--tests", required=True, help="promptfoo 产物路径（test-cases*.yaml 或 *.json）")
    ap.add_argument("--rules", help="结构化词表路径（镜像 rag.guardrail.rules.*-location；缺省 bundled 基线）")
    ap.add_argument("--csv", default="", help="CSV 兼容源（镜像运维侧带外扩充键）")
    args = ap.parse_args()

    tests_path = Path(args.tests)
    if not tests_path.exists():
        print(f"用例文件不存在: {tests_path}")
        return 2
    try:
        cases = load_cases(tests_path)
    except Exception as exc:  # noqa: BLE001——形态不识别给出明确指引
        print(f"用例文件解析失败（{exc.__class__.__name__}）：请确认形态为 "
              "promptfoo redteam generate 产物（YAML tests 列表或 JSON）")
        return 1

    rules_path = Path(args.rules) if args.rules else pm.DEFAULT_RULES["injection"]
    if args.rules and not rules_path.exists():
        print(f"结构化词表不存在（运行时将回落内置缺省）: {rules_path}")
        rules_path = pm.DEFAULT_RULES["injection"]
    merged = {}
    for rule in pm.load_csv_compat(args.csv):
        merged[rule["id"]] = rule
    for rule in pm.load_structured(rules_path):
        merged[rule["id"]] = rule
    rules = list(merged.values())
    blocks = sum(1 for r in rules if r["action"] == "BLOCK")
    print(f"词表装载: {len(rules)} 条（BLOCK {blocks} / FLAG {len(rules) - blocks}）源={rules_path.name}")
    print(f"用例装载: {len(cases)} 条，源={tests_path.name}")

    totals = {"BLOCK": 0, "KEYWORD": 0, "TRIGGER": 0, "SILENT": 0}
    by_plugin = {}
    by_strategy = {}
    missing = []
    iterative = []      # 迭代式策略种子态：载荷运行时构造，不参四分区
    seed_totals = {"BLOCK": 0, "KEYWORD": 0, "TRIGGER": 0, "SILENT": 0}
    for seq, item in enumerate(cases, 1):
        case_id = f"test-{seq:03d}-{item['plugin']}-{item['strategy']}"
        prompt = item.get("prompt")
        if not prompt:
            missing.append(item)
            continue
        view = pm.normalize(prompt)
        hits = [r for r in rules if pm.match(r, view)]
        cls = classify(hits)
        if is_iterative(item):
            # 迭代式种子态：种子分区只作参照副报（运行时载荷与种子无关）
            iterative.append(item)
            seed_totals[cls] += 1
            continue
        totals[cls] += 1
        zeros = {"BLOCK": 0, "KEYWORD": 0, "TRIGGER": 0, "SILENT": 0}
        by_plugin.setdefault(item["plugin"], dict(zeros))[cls] += 1
        by_strategy.setdefault(item["strategy"], dict(zeros))[cls] += 1
        if cls != "SILENT":
            hit_ids = ",".join(r["id"] for r in hits)
            families = sorted({r["family"] for r in hits})
            print(f"  {case_id} → {cls}（命中 {hit_ids}，族系 {families}）")

    counted = sum(totals.values())
    print("── 四分区汇总（仅确定性载荷：base 与逐字编码策略）──")
    print(f"BLOCK 直拦 {totals['BLOCK']} / KEYWORD 压制 {totals['KEYWORD']} / "
          f"TRIGGER 可触发 {totals['TRIGGER']} / SILENT 静默 {totals['SILENT']}（合计 {counted}）")
    if iterative:
        strategies = Counter(m["strategy"] for m in iterative)
        print(f"── 迭代式策略种子态（{len(iterative)} 条，不参四分区：载荷运行时构造）──")
        print("  strategy 分布: " + ", ".join(f"{k}×{c}" for k, c in sorted(strategies.items())))
        print(f"  种子分区参照（非运行时载荷）: BLOCK {seed_totals['BLOCK']} / "
              f"KEYWORD {seed_totals['KEYWORD']} / TRIGGER {seed_totals['TRIGGER']} / "
              f"SILENT {seed_totals['SILENT']}")
        print("  防御归因走运行时：审计日志（REJECTED 三态）+ rag.guardrail.l2.triggered 指标")
    if missing:
        # 内容盲结构诊断：只回键名/分布/计数，零内容回显——定位「生成物本无
        # vars.prompt」（多变量形态）还是「解析器形态盲区」（如 flow 映射）
        topkeys = Counter(k for m in missing for k in m.get("_topkeys", set()))
        varskeys = Counter(k for m in missing for k in m.get("_varskeys", set()))
        strategies = Counter(m["strategy"] for m in missing)
        plugins = Counter(m["plugin"] for m in missing)
        print(f"── 缺 prompt 用例结构诊断（{len(missing)} 条，内容盲：只回键名/分布）──")
        print("  顶层键名: " + ", ".join(f"{k}×{c}" for k, c in sorted(topkeys.items()))
              if topkeys else "  顶层键名: （零键记录——列表项内容形态未被解析器识别，回传处置）")
        print("  vars 键名: " + (", ".join(f"{k}×{c}" for k, c in sorted(varskeys.items()))
                                if varskeys else "（该批无展开 vars 段——flow 映射或 vars 缺席）"))
        print("  strategy 分布: " + ", ".join(f"{k}×{c}" for k, c in sorted(strategies.items())))
        print("  plugin 分布: " + ", ".join(f"{k}×{c}" for k, c in sorted(plugins.items())))
        print("  判读提示: ① 顶层键名含 vars 但 vars 键名为空 → flow 映射内联形态（解析盲区，回传处置）")
        print("           ② 顶层键名无 vars → 生成物本无 prompt 变量（多变体形态，看替代键名）")
        print("           ③ vars 键名非 prompt → 多变量生成形态，记替代变量名另议")
        print("           ④ vars 键名含 prompt 但未录内容 → prompt 空值/空块标量形态")
    print("── 逐 plugin 分解（确定性载荷）──")
    for name in sorted(by_plugin):
        b = by_plugin[name]
        print(f"  {name}: BLOCK {b['BLOCK']} / KEYWORD {b['KEYWORD']} / "
              f"TRIGGER {b['TRIGGER']} / SILENT {b['SILENT']}")
    print("── 逐 strategy 分解（确定性载荷）──")
    for name in sorted(by_strategy):
        b = by_strategy[name]
        print(f"  {name}: BLOCK {b['BLOCK']} / KEYWORD {b['KEYWORD']} / "
              f"TRIGGER {b['TRIGGER']} / SILENT {b['SILENT']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
