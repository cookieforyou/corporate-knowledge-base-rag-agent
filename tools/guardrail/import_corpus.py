#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
安全簇① T6 —— 注入语料带外导入脚本（用户本地执行，AI 零接触内容）

从 inbox 目录读取攻击样本（JSONL），逐条 Base64 编码 + SHA-256 指纹锚点后
并入 Golden 注入语料（kb-eval/src/main/resources/golden/injection-qa.json，
编码引用形态，安全簇① T2 第七节交付纪律）。

内容纪律（第七节条 1/2/4）：
  - 样本内容只被本脚本程序化读取/编码/写盘；stdout 仅回显
    计数 / 新增 ID 段 / attackType 分布 / SHA-256 指纹前缀 / 带行号错误，
    绝不回显样本内容。
  - inbox 建议放仓库外本地路径（明文永不进仓库）；导入完成后 inbox 自行处置。

inbox 文件格式（.jsonl，每行一个对象，文件间合并）：
  {"question": "...", "attackType": "DIRECT|ENCODING_BYPASS|JAILBREAK|MULTILINGUAL",
   "encoding": "base64（可选——question 已是 Base64 编码态时声明，脚本校验后原样落盘）"}

attackType 语义（与门禁契约对齐，v2.42/T6 映射定案见 12 章 §12.7）：
  DIRECT            直接表达，归一化后必命中 BLOCK 档（门禁子集）
  ENCODING_BYPASS   编码/变形字面，归一前不命中 KEYWORD 档、归一后命中（门禁子集）
  JAILBREAK         越狱引导，L1 盲区观察集（不得命中任何 BLOCK 档）
  MULTILINGUAL      非主语种表达，L1 盲区观察集（不得命中任何 BLOCK 档）

用法：
  python3 tools/guardrail/import_corpus.py --inbox <目录> [--dry-run]

幂等：按解码值 SHA-256 与既有语料去重，重复运行不产生重复样本。
导入后必须运行 `mvn -q --no-transfer-progress -pl kb-eval -am test` 复跑
GoldenDatasetLoaderTest 门禁契约校验新样本（契约单一事实源在测试侧，
本脚本不重复实现契约判定，防双源漂移）。
"""

import argparse
import base64
import hashlib
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CORPUS_FILE = REPO_ROOT / "kb-eval/src/main/resources/golden/injection-qa.json"

ATTACK_TYPES = {
    "DIRECT": "inj-direct",
    "ENCODING_BYPASS": "inj-encoding",
    "JAILBREAK": "inj-jailbreak",
    "MULTILINGUAL": "inj-multilingual",
}
ID_SUFFIX = re.compile(r"^.+-(\d+)$")


class ImportFailure(Exception):
    """带定位的导入错误（消息仅含结构信息，不含样本内容）"""


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load_corpus():
    if not CORPUS_FILE.exists():
        raise ImportFailure(f"语料文件不存在: {CORPUS_FILE}")
    return json.loads(CORPUS_FILE.read_text(encoding="utf-8"))


def read_inbox(inbox_dir: Path):
    """读取 inbox 全部样本；返回 (items, file_count)。内容只留指纹，不回显。"""
    if not inbox_dir.is_dir():
        raise ImportFailure(f"inbox 目录不存在: {inbox_dir}")
    items, file_count = [], 0
    for p in sorted(inbox_dir.iterdir()):
        if p.suffix != ".jsonl":
            continue
        for lineno, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            loc = f"{p.name}:{lineno}"
            try:
                obj = json.loads(line)
            except json.JSONDecodeError as e:
                raise ImportFailure(f"{loc} JSON 解析失败（{e.msg} 列 {e.colno}）")
            question = str(obj.get("question", "")).strip()
            if not question:
                raise ImportFailure(f"{loc} question 为空")
            attack_type = str(obj.get("attackType", "")).strip().upper()
            if attack_type not in ATTACK_TYPES:
                raise ImportFailure(f"{loc} attackType 非法（须 {'|'.join(ATTACK_TYPES)}）")
            encoding = str(obj.get("encoding", "") or "").strip().lower()
            if encoding not in ("", "base64"):
                raise ImportFailure(f"{loc} encoding 仅支持 base64 或省略")
            if encoding == "base64":
                try:
                    decoded = base64.b64decode(question, validate=True).decode("utf-8")
                except Exception:
                    raise ImportFailure(f"{loc} 声明 encoding=base64 但值非合法 Base64 编码")
                b64 = question
            else:
                decoded = question
                b64 = base64.b64encode(question.encode("utf-8")).decode("ascii")
            items.append({"attackType": attack_type, "b64": b64,
                          "sha256": sha256_hex(decoded)})
        file_count += 1
    return items, file_count


def main() -> int:
    ap = argparse.ArgumentParser(description="注入语料带外导入（编码引用落盘；输出仅计数/ID 段/指纹）")
    ap.add_argument("--inbox", required=True, help="inbox 目录（建议仓库外本地路径）")
    ap.add_argument("--dry-run", action="store_true", help="只报告不落盘")
    args = ap.parse_args()

    corpus = load_corpus()
    fingerprints = {item.get("questionSha256") for item in corpus}
    max_suffix = {}
    for item in corpus:
        m = ID_SUFFIX.match(item["id"])
        prefix = item["id"].rsplit("-", 1)[0]
        if m:
            max_suffix[prefix] = max(max_suffix.get(prefix, 0), int(m.group(1)))

    items, file_count = read_inbox(Path(args.inbox).expanduser())
    planned, dup = [], 0
    for item in items:
        if item["sha256"] in fingerprints:
            dup += 1
            continue
        fingerprints.add(item["sha256"])
        planned.append(item)

    added = []
    for item in planned:
        prefix = ATTACK_TYPES[item["attackType"]]
        seq = max_suffix.get(prefix, 0) + 1
        max_suffix[prefix] = seq
        rid = f"{prefix}-{seq:02d}"
        item["id"] = rid
        added.append(rid)

    dist = {}
    for item in planned:
        dist[item["attackType"]] = dist.get(item["attackType"], 0) + 1

    print(f"inbox 文件 {file_count} 个；样本 {len(items)} 条"
          f"（新增 {len(planned)} / 指纹重复跳过 {dup}）")
    if dist:
        print("attackType 分布: " + ", ".join(f"{k}={v}" for k, v in sorted(dist.items())))
    if added:
        print(f"新增 ID: {', '.join(added)}")
    if args.dry_run:
        print("[dry-run] 未写盘")
        return 0
    if not planned:
        return 0

    for item in planned:
        corpus.append({
            "id": item["id"],
            "category": "INJECTION",
            "attackType": item["attackType"],
            "question": item["b64"],
            "questionEncoding": "base64",
            "questionSha256": item["sha256"],
        })
    CORPUS_FILE.write_text(
        json.dumps(corpus, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"语料已更新: {CORPUS_FILE.relative_to(REPO_ROOT)}（累计 {len(corpus)} 条）")
    print("下一步: mvn -q --no-transfer-progress -pl kb-eval -am test"
          "（门禁契约校验新样本——单一事实源）")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ImportFailure as e:
        print(f"导入失败: {e}", file=sys.stderr)
        sys.exit(1)
