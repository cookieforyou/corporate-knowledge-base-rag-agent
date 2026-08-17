#!/usr/bin/env python3
"""护栏词表匹配探针（安全簇① T8 E2E 配套，内容盲形态）

用途：在不启动服务的前提下，以与运行时等价的语义复算一条消息对护栏词表的
命中情况——用于排查「FLAG 观察档预期命中但指标/审计无记录」类问题
（先排除消息本身未命中 REGEX 结构模式的可能，再查部署形态）。

与运行时同源语义（TextSanitizer + GuardrailRulesLoader）：
  - 归一化检测视图：零宽剥离（ZWSP/ZWNJ/ZWJ/词连接符/BOM/软连字符/蒙古文
    元音分隔符）→ NFKC → 空白折叠（ASCII 空白类，对齐 Java \\s）
  - KEYWORD 小写子串匹配；REGEX 预编译 find（大小写不敏感）
  - 双源合并：CSV 兼容源 → 结构化文件，按 id 后者覆盖

输出纪律（§7 条 1/4）：只回显规则 ID / 动作 / 族系 / 计数 / 指纹——
不回显消息原文与词项值。消息经 --message-file 从仓库外文件读入
（勿经命令行参数传递，防 shell 历史留痕）。

用法：
  python3 tools/guardrail/probe_match.py --message-file /tmp/probe-msg.txt
  python3 tools/guardrail/probe_match.py --message-file /tmp/msg.txt \\
      --rules file:/opt/kb/injection-rules.yml --csv "a,b"   # 复算生产覆盖形态
  python3 tools/guardrail/probe_match.py --side output --message-file /tmp/msg.txt
"""

import argparse
import base64
import re
import sys
import unicodedata
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RULES = {
    "injection": REPO_ROOT / "kb-commons/src/main/resources/guardrail/injection-rules.yml",
    "output": REPO_ROOT / "kb-commons/src/main/resources/guardrail/output-rules.yml",
}

INVISIBLE = re.compile("[\u200B\u200C\u200D\u2060\uFEFF\u00AD\u180E]")
# Java Pattern \s 默认 ASCII 类（不含 Unicode 空白），与运行时对齐
WHITESPACE_RUN = re.compile("[ \t\n\x0b\f\r]+")


def normalize(text: str) -> str:
    """S1 归一化检测视图（TextSanitizer.normalize 等价实现）"""
    result = INVISIBLE.sub("", text)
    result = unicodedata.normalize("NFKC", result)
    return WHITESPACE_RUN.sub(" ", result)


def load_structured(path: Path):
    rules = []
    cur = None
    for line in path.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^(\s*)-\s+([\w.-]+):\s*(.*)$", line)
        if m:
            cur = {m.group(2): m.group(3).strip()}
            rules.append(cur)
            continue
        m = re.match(r"^(\s+)([\w.-]+):\s*(.*)$", line)
        if m and cur is not None:
            cur[m.group(2)] = m.group(3).strip()
    parsed = []
    for entry in rules:
        rid = entry.get("id", "").strip()
        raw = entry.get("value", "").strip().strip("'\"")
        if not rid or not raw:
            continue
        try:
            decoded = base64.b64decode(raw, validate=True).decode("utf-8")
        except Exception:
            print(f"  WARN 词项 {rid} Base64 解码失败，跳过")
            continue
        parsed.append({
            "id": rid,
            "type": (entry.get("type") or "KEYWORD").strip().upper(),
            "action": (entry.get("action") or "BLOCK").strip().upper(),
            "family": (entry.get("family") or "UNCLASSIFIED").strip() or "UNCLASSIFIED",
            "enabled": (entry.get("enabled") or "true").strip().lower() != "false",
            "decoded": decoded,
        })
    return parsed


def load_csv_compat(csv: str):
    rules = []
    for i, token in enumerate(csv.split(",")):
        trimmed = token.strip()
        if trimmed:
            rules.append({"id": f"legacy-csv-{i}", "type": "KEYWORD", "action": "BLOCK",
                          "family": "UNCLASSIFIED", "enabled": True,
                          "decoded": trimmed.lower()})
    return rules


def match(rule, view: str) -> bool:
    if not rule["enabled"] or not view:
        return False
    if rule["type"] == "REGEX":
        return re.search(rule["decoded"], view, re.IGNORECASE) is not None
    return rule["decoded"].lower() in view.lower()


def main() -> int:
    ap = argparse.ArgumentParser(description="护栏词表匹配探针（内容盲：只回显 ID/计数/族系）")
    ap.add_argument("--message-file", required=True, help="消息文件（建议仓库外路径，整文件为一条消息）")
    ap.add_argument("--side", choices=["injection", "output"], default="injection")
    ap.add_argument("--rules", help="结构化词表路径（镜像 rag.guardrail.rules.*-location；缺省 bundled 基线）")
    ap.add_argument("--csv", default="", help="CSV 兼容源（镜像 rag.guardrail.*.*-keywords/blacklist）")
    args = ap.parse_args()

    msg_path = Path(args.message_file)
    if not msg_path.exists():
        print(f"消息文件不存在: {msg_path}")
        return 2
    message = msg_path.read_text(encoding="utf-8").rstrip("\n")
    if not message:
        print("消息为空")
        return 2

    rules_path = Path(args.rules) if args.rules else DEFAULT_RULES[args.side]
    if not rules_path.exists():
        print(f"结构化词表不存在（运行时将回落内置缺省）: {rules_path}")
        rules_path = DEFAULT_RULES[args.side]

    merged = {}
    for rule in load_csv_compat(args.csv):
        merged[rule["id"]] = rule
    for rule in load_structured(rules_path):
        merged[rule["id"]] = rule
    rules = list(merged.values())
    blocks = sum(1 for r in rules if r["action"] == "BLOCK")
    print(f"词表装载: {len(rules)} 条（BLOCK {blocks} / FLAG {len(rules) - blocks}）源={rules_path.name}")

    view = normalize(message)
    print(f"归一化检测视图长度: {len(view)}（原文长度 {len(message)}）")

    matched = [r for r in rules if match(r, view)]
    if not matched:
        print("命中: 0 条——消息未命中任何词项（REGEX 需整模式命中，单词片段不算）")
        return 0
    for r in matched:
        print(f"  命中 {r['id']}: type={r['type']}, action={r['action']}, family={r['family']}")
    blocked = any(r["action"] == "BLOCK" for r in matched)
    if blocked:
        print("判定: 含 BLOCK 命中 → 运行时拒绝（不计 FLAG）")
    else:
        families = sorted({r["family"] for r in matched})
        print(f"判定: 仅 FLAG 命中 → 运行时放行 + 计数 rag.guardrail.flagged（side={args.side}，族系 {families}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
