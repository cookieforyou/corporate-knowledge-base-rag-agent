#!/usr/bin/env python3
"""护栏词表与攻击语料编码迁移脚本（安全簇① T2，设计 12.7 词表工程）。

职责：把存量「字面形态」的词表值与样本问题迁移为「逐条 Base64 编码态」，
原字面源清空。脚本本体零样本字面——值只被程序读取/编码/写盘，stdout 仅输出
计数、ID 段、指纹（SHA-256 前 12 位）、长度与带行号错误，不回显任何词值或样本内容。

迁移源 → 目标：
  1. kb-commons TextSanitizer.DEFAULT_INJECTION_KEYWORDS 字面块
       → kb-commons resources guardrail/injection-rules.yml（编码区）
  2. kb-ai-core application-ai.yml 两 CSV 键（input.injection-keywords / output.blacklist）
       → injection-rules.yml / output-rules.yml 编码区（按值哈希去重），原键值清空
  3. kb-eval golden/injection-qa.json 48 条样本
       → 原地改写为引用形态：question=Base64 + questionEncoding=base64
         + questionSha256 哈希锚点（id/category/attackType 保留）
  4. 指纹清单 → tools/guardrail/migration-manifest.json（仅元数据，无值）

安全装置：
  - 幂等守卫：目标已含编码产物（yml 已有 rules / 语料已带 questionEncoding）时中止，
    非 --force 不覆写；
  - 解析失败只报「文件 + 行号 + 结构性原因」，异常消息不携带内容；
  - 语料哈希锚点供加载层解码后校验（腐化即 fail-fast）。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

DEFAULT_PATHS = {
    "sanitizer": REPO_ROOT / "kb-commons/src/main/java/com/enterprise/kb/commons/security/TextSanitizer.java",
    "ai_yml": REPO_ROOT / "kb-ai-core/src/main/resources/application-ai.yml",
    "corpus": REPO_ROOT / "kb-eval/src/main/resources/golden/injection-qa.json",
    "injection_rules": REPO_ROOT / "kb-commons/src/main/resources/guardrail/injection-rules.yml",
    "output_rules": REPO_ROOT / "kb-commons/src/main/resources/guardrail/output-rules.yml",
    "manifest": REPO_ROOT / "tools/guardrail/migration-manifest.json",
}

INJECTION_HEADER = """\
# 注入检测词表（安全簇① A1/T2，设计 12.7 词表工程）
#
# 词项模型：id / family / lang / type / value / action / enabled
#   id      —— 词项唯一标识（词表运营 / 审计 / 清单引用锚点）
#   family  —— 攻击族系七分法中性枚举（GuardrailFamily）：INSTRUCTION_OVERRIDE /
#              ROLE_HIJACK / INFO_EXTRACTION / ENCODING_OBFUSCATION / MULTILINGUAL /
#              JAILBREAK / TOOL_INDUCED（未标注用 UNCLASSIFIED 兼容档；
#              T2 存量迁移项 family 待人工标注，指纹见 tools/guardrail/migration-manifest.json）
#   lang    —— 语种标注（zh / en / ja / …）
#   type    —— KEYWORD（大小写不敏感子串）| REGEX（结构化模式，CASE_INSENSITIVE 编译）
#   value   —— 【逐条 Base64 编码态】，加载层解码消费（第七节敏感词交付纪律条 2，
#              编码纯为交付形态约束——防 AI 辅助链路误检测，非安全机制）
#   action  —— BLOCK（命中即拒）| FLAG（观察档：计数+审计标记不拒绝）
#   enabled —— 停用开关
#
# 双源合并：本文件（内置基线已 T2 迁入）∪ rag.guardrail.input.injection-keywords（兼容并入）。
# 误伤铁律：领域裸词不入 BLOCK 档；可疑词先 FLAG，零误伤后转 BLOCK。
rules:
"""

OUTPUT_HEADER = """\
# 输出检测词表（安全簇① A1/A3/T2，设计 12.7 词表工程）
#
# 词项模型同 injection-rules.yml；family 取输出分类三分类名（中性）：
#   BUSINESS_CONFIDENTIAL（业务保密）/ COMPLIANCE_SENSITIVE（合规敏感）/
#   COMPETITOR_COMPARISON（竞品对比）——各分类独立 action 与指标子项（A3）。
# value 同样逐条 Base64 编码态存储，加载层解码消费（第七节条 2）。
# （T2 存量黑名单迁入，family 待人工标注，指纹见 tools/guardrail/migration-manifest.json）
#
# 双源合并：本文件 ∪ rag.guardrail.output.blacklist（兼容并入）。
rules:
"""


class MigrationError(RuntimeError):
    """结构性错误——消息只含文件/行号/原因，不含内容。"""


@dataclass
class Entry:
    entry_id: str
    source: str
    value: str

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.value.encode("utf-8")).hexdigest()

    @property
    def b64(self) -> str:
        return base64.b64encode(self.value.encode("utf-8")).decode("ascii")

    @property
    def lang(self) -> str:
        return "zh" if any("一" <= c <= "鿿" for c in self.value) else "en"

    def to_yaml(self) -> str:
        return (
            f"- id: {self.entry_id}\n"
            f"  family: UNCLASSIFIED\n"
            f"  lang: {self.lang}\n"
            f"  type: KEYWORD\n"
            f"  value: {self.b64}\n"
            f"  action: BLOCK\n"
            f"  enabled: true\n"
        )


@dataclass
class Plan:
    injection: list[Entry] = field(default_factory=list)
    output: list[Entry] = field(default_factory=list)
    corpus_items: list[dict] = field(default_factory=list)
    corpus_count: int = 0
    deduped: int = 0
    yml_lines: dict[str, int] = field(default_factory=dict)


# ── 源 1：Java 字面块解析 ──

JAVA_UNESCAPE = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\", "'": "'"}


def java_unescape(literal: str, where: str) -> str:
    out: list[str] = []
    i = 0
    while i < len(literal):
        c = literal[i]
        if c != "\\":
            out.append(c)
            i += 1
            continue
        if i + 1 >= len(literal):
            raise MigrationError(f"{where}: 字面块转义序列不完整")
        nxt = literal[i + 1]
        if nxt == "u":
            if i + 5 >= len(literal) + 1:
                raise MigrationError(f"{where}: unicode 转义长度不足")
            out.append(chr(int(literal[i + 2:i + 6], 16)))
            i += 6
        elif nxt in JAVA_UNESCAPE:
            out.append(JAVA_UNESCAPE[nxt])
            i += 2
        else:
            raise MigrationError(f"{where}: 未知转义序列")
    return "".join(out)


def parse_java_builtin(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    match = re.search(
        r"DEFAULT_INJECTION_KEYWORDS\s*=\s*List\.of\((.*?)\)\s*;", text, re.DOTALL
    )
    if not match:
        raise MigrationError(f"{path.name}: 未找到 DEFAULT_INJECTION_KEYWORDS 字面块")
    block = match.group(1)
    block_line = text[: match.start()].count("\n") + 1
    literals = re.findall(r'"((?:[^"\\]|\\.)*)"', block)
    if not literals:
        raise MigrationError(f"{path.name}:{block_line}: 字面块内未解析到字符串项")
    return [java_unescape(lit, f"{path.name}:{block_line}") for lit in literals]


# ── 源 2：application-ai.yml CSV 键解析（缩进作用域跟踪，不引入 YAML 依赖） ──


def parse_ai_yml(path: Path) -> tuple[list[str], list[str], dict[str, int]]:
    """返回 (injection CSV 项, output CSV 项, 键所在行号)。"""
    lines = path.read_text(encoding="utf-8").splitlines()
    guardrail_indent = None
    scope = None  # "input" | "output" | None
    scope_indent = None
    found: dict[str, tuple[int, str]] = {}

    for lineno, raw in enumerate(lines, 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        stripped = raw.strip()
        key = stripped.split(":", 1)[0].strip()

        if key == "guardrail" and stripped.endswith(":"):
            guardrail_indent = indent
            scope = None
            continue
        if guardrail_indent is None:
            continue
        if indent <= guardrail_indent:
            guardrail_indent = None  # 离开 guardrail 块
            scope = None
            continue
        if key in ("input", "output") and stripped.endswith(":"):
            scope = key
            scope_indent = indent
            continue
        if scope and indent <= scope_indent:
            scope = None
        if scope == "input" and key == "injection-keywords":
            found["injection"] = (lineno, stripped.split(":", 1)[1].strip())
        elif scope == "output" and key == "blacklist":
            found["output"] = (lineno, stripped.split(":", 1)[1].strip())

    def items(name: str) -> list[str]:
        if name not in found:
            raise MigrationError(f"{path.name}: 未找到 {name} 侧 CSV 键")
        _, value = found[name]
        return [t.strip() for t in value.split(",") if t.strip()]

    return items("injection"), items("output"), {k: v[0] for k, v in found.items()}


def empty_ai_yml_values(path: Path, line_numbers: dict[str, int]) -> None:
    lines = path.read_text(encoding="utf-8").splitlines()
    for name, lineno in line_numbers.items():
        line = lines[lineno - 1]
        key_part = line.split(":", 1)[0]
        lines[lineno - 1] = f"{key_part}:"
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


# ── 源 3：攻击语料引用形态改写 ──


def encode_corpus(path: Path, plan: Plan) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise MigrationError(f"{path.name}: 顶层结构应为数组")
    for idx, item in enumerate(data):
        sid = item.get("id") or f"<index:{idx}>"
        for required in ("id", "category", "attackType", "question"):
            if required not in item:
                raise MigrationError(f"{path.name}: 样本 {sid} 缺少字段 {required}")
        if "questionEncoding" in item:
            raise MigrationError(f"{path.name}: 样本 {sid} 已带 questionEncoding（疑似已迁移）")
        question = item["question"]
        if not isinstance(question, str) or not question.strip():
            raise MigrationError(f"{path.name}: 样本 {sid} question 为空")
        encoded = base64.b64encode(question.encode("utf-8")).decode("ascii")
        plan.corpus_items.append({
            "id": item["id"],
            "category": item["category"],
            "attackType": item["attackType"],
            "question": encoded,
            "questionEncoding": "base64",
            "questionSha256": hashlib.sha256(question.encode("utf-8")).hexdigest(),
        })
    plan.corpus_count = len(data)


# ── 装配 ──


def assemble(args: argparse.Namespace) -> Plan:
    plan = Plan()
    seen: set[str] = set()
    seq = 0

    def add(source: str, values: list[str]) -> None:
        nonlocal seq
        for value in values:
            digest = hashlib.sha256(value.encode("utf-8")).hexdigest()
            if digest in seen:
                plan.deduped += 1
                continue
            seen.add(digest)
            seq += 1
            plan.injection.append(Entry(f"builtin-inj-{seq:02d}", source, value))

    add("java-builtin", parse_java_builtin(args.sanitizer))
    inj_csv, out_csv, yml_lines = parse_ai_yml(args.ai_yml)
    add("ai-yml-injection-keywords", inj_csv)
    plan.yml_lines = yml_lines

    out_seen: set[str] = set()
    out_seq = 0
    for value in out_csv:
        digest = hashlib.sha256(value.encode("utf-8")).hexdigest()
        if digest in out_seen:
            plan.deduped += 1
            continue
        out_seen.add(digest)
        out_seq += 1
        plan.output.append(Entry(f"builtin-out-{out_seq:02d}", "ai-yml-blacklist", value))

    encode_corpus(args.corpus, plan)
    return plan


def report(plan: Plan) -> None:
    def fingerprint(entries: list[Entry]) -> None:
        for e in entries:
            print(f"  {e.entry_id}  sha256:{e.sha256[:12]}  len:{len(e.value)}  lang:{e.lang}  src:{e.source}")

    print(f"[注入侧词项] {len(plan.injection)} 条（ID 段 builtin-inj-01..{len(plan.injection):02d}）")
    fingerprint(plan.injection)
    print(f"[输出侧词项] {len(plan.output)} 条（ID 段 builtin-out-01..{len(plan.output):02d}）")
    fingerprint(plan.output)
    print(f"[攻击语料] {plan.corpus_count} 条改写为引用形态（base64 + sha256 锚点）")
    print(f"[去重] 值哈希重复跳过 {plan.deduped} 条")
    print(f"[清空] application-ai.yml CSV 键值行号: "
          + ", ".join(f"{name}=L{ln}" for name, ln in sorted(plan.yml_lines.items())))


def write_all(args: argparse.Namespace, plan: Plan) -> None:
    args.injection_rules.write_text(
        INJECTION_HEADER + "".join(e.to_yaml() for e in plan.injection), encoding="utf-8")
    args.output_rules.write_text(
        OUTPUT_HEADER + "".join(e.to_yaml() for e in plan.output), encoding="utf-8")
    args.corpus.write_text(
        json.dumps(plan.corpus_items, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    empty_ai_yml_values(args.ai_yml, plan.yml_lines)
    manifest = {
        "task": "安全簇① T2 存量字面编码迁移",
        "note": "仅元数据（指纹/长度/族系占位），不含词值；family=PENDING_ANNOTATION 待人工标注",
        "sources": {
            "java-builtin": sum(1 for e in plan.injection if e.source == "java-builtin"),
            "ai-yml-injection-keywords": sum(1 for e in plan.injection if e.source == "ai-yml-injection-keywords"),
            "ai-yml-blacklist": len(plan.output),
            "corpus-questions": plan.corpus_count,
        },
        "entries": [
            {
                "id": e.entry_id, "source": e.source, "family": "PENDING_ANNOTATION",
                "lang": e.lang, "type": "KEYWORD", "action": "BLOCK",
                "sha256": e.sha256, "char_len": len(e.value),
            }
            for e in plan.injection + plan.output
        ],
    }
    args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def guard_already_migrated(args: argparse.Namespace) -> None:
    inj_text = args.injection_rules.read_text(encoding="utf-8")
    if re.search(r"^rules:\s*\n\s*- id:", inj_text, re.MULTILINE):
        raise MigrationError("injection-rules.yml 已含编码词项（疑似已迁移，--force 跳过本守卫）")
    data = json.loads(args.corpus.read_text(encoding="utf-8"))
    if any("questionEncoding" in item for item in data):
        raise MigrationError("injection-qa.json 已含 questionEncoding（疑似已迁移，--force 跳过本守卫）")


def main() -> int:
    parser = argparse.ArgumentParser(description="护栏词表与攻击语料编码迁移（零内容回显）")
    parser.add_argument("--dry-run", action="store_true", help="只报告迁移计划，不写盘")
    parser.add_argument("--force", action="store_true", help="跳过幂等守卫（谨慎）")
    for name, default in DEFAULT_PATHS.items():
        parser.add_argument(f"--{name.replace('_', '-')}", type=Path, default=default)
    args = parser.parse_args()

    try:
        if not args.force:
            guard_already_migrated(args)
        plan = assemble(args)
        report(plan)
        if args.dry_run:
            print("[dry-run] 未写盘")
            return 0
        write_all(args, plan)
        print("[完成] 已写入：", ", ".join(str(p) for p in (
            args.injection_rules, args.output_rules, args.corpus, args.manifest)))
        return 0
    except MigrationError as e:
        print(f"[迁移中止] {e}", file=sys.stderr)
        return 2
    except (OSError, json.JSONDecodeError, ValueError) as e:
        print(f"[迁移中止] 结构性错误: {type(e).__name__}（内容不外显）", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
