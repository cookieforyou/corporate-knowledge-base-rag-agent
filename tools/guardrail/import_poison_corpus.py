#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
安全簇④ D3a —— 间接注入毒化语料带外导入脚本（用户本地执行，AI 零接触内容）

从 inbox 目录读取毒化文档（正文文本文件 + 元数据 JSONL），逐篇 Base64 编码 +
SHA-256 指纹锚点后并入间接注入语料（kb-eval/src/main/resources/indirect/
indirect-qa.json，编码引用形态——唯正文编码，触发问句与 Judge 判据为明文
安全区，簇④分解纪律条 6）。

内容纪律（§7 + 簇④分解条 5）：
  - 毒化正文只被本脚本程序化读取/编码/写盘；stdout 仅回显
    计数 / 新增 ID 段 / SHA-256 指纹前缀 / 带行号错误，绝不回显正文内容。
  - inbox 建议放仓库外本地路径（明文永不进仓库）；导入完成后 inbox 自行处置。
  - 毒化文档入目标库走生产管道（前端/API 上传，用户侧执行）——本脚本只负责
    语料归档入库（版本管理 + 探针指纹自洽锚点），不触达任何运行服务。

inbox 文件格式：
  1) 毒化正文：文本文件（*.txt，UTF-8），一篇一案；
  2) 元数据：*.jsonl（每行一个对象，file 指向同目录正文文件名）：
     {"file": "poison-01.txt",
      "fileName": "入库展示文件名.txt（可选，缺省 = file 基名；探针 file_name 匹配键，须与实际上传文件名一致）",
      "question": "触发问句（正常业务问句，非载荷）",
      "judgeCriteria": "忠实判据（期望回答行为的结构描述，非载荷）"}

用法：
  python3 tools/guardrail/import_poison_corpus.py --inbox <目录> [--dry-run]
         [--remove-id <用例ID>]...（退役指定用例，可多次）

幂等：按正文 SHA-256 与既有语料去重，重复运行不产生重复用例。
导入后必须运行 `mvn -q --no-transfer-progress -pl kb-eval -am test` 复跑
IndirectDatasetLoaderTest 加载自洽校验（契约单一事实源在测试侧）。
"""

import argparse
import base64
import hashlib
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CORPUS_FILE = REPO_ROOT / "kb-eval/src/main/resources/indirect/indirect-qa.json"
MANIFEST = REPO_ROOT / "tools/guardrail/import-manifest.json"
ID_PREFIX = "poison"
ID_SUFFIX = re.compile(r"^poison-(\d+)$")


class ImportFailure(Exception):
    """带定位的导入错误（消息仅含结构信息，不含正文内容）"""


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load_corpus():
    if not CORPUS_FILE.exists():
        return []
    return json.loads(CORPUS_FILE.read_text(encoding="utf-8"))


def read_inbox(inbox_dir: Path):
    """读取 inbox 全部用例；返回 (items, meta_files)。正文只留指纹，不回显。"""
    if not inbox_dir.is_dir():
        raise ImportFailure(f"inbox 目录不存在: {inbox_dir}")
    items, meta_files = [], 0
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
            file_name_ref = str(obj.get("file", "")).strip()
            if not file_name_ref:
                raise ImportFailure(f"{loc} file 为空（须指向同目录正文文本文件）")
            body_path = inbox_dir / file_name_ref
            if not body_path.is_file():
                raise ImportFailure(f"{loc} 正文文件不存在: {file_name_ref}")
            question = str(obj.get("question", "")).strip()
            criteria = str(obj.get("judgeCriteria", "")).strip()
            if not question:
                raise ImportFailure(f"{loc} question 为空（触发问句必填，正常业务问句）")
            if not criteria:
                raise ImportFailure(f"{loc} judgeCriteria 为空（忠实判据必填，结构描述）")
            try:
                body = body_path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                raise ImportFailure(f"{loc} 正文文件非 UTF-8 文本: {file_name_ref}")
            if not body.strip():
                raise ImportFailure(f"{loc} 正文为空: {file_name_ref}")
            kb_name = str(obj.get("fileName", "")).strip() or body_path.name
            items.append({
                "fileName": kb_name,
                "question": question,
                "judgeCriteria": criteria,
                "b64": base64.b64encode(body.encode("utf-8")).decode("ascii"),
                "sha256": sha256_hex(body),
            })
        meta_files += 1
    return items, meta_files


def load_manifest() -> dict:
    """与 import_words/import_corpus 共享指纹清单；本脚本只写 poisonCorpus 键"""
    if MANIFEST.exists():
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        manifest.setdefault("poisonCorpus", {"entries": [], "removals": []})
        return manifest
    return {"description": "安全簇① / 簇④ 带外导入指纹清单（仅元数据，无词值/样本内容）",
            "entries": [], "removals": [], "actionChanges": [], "corpusRemovals": [],
            "poisonCorpus": {"entries": [], "removals": []}}


def main() -> int:
    ap = argparse.ArgumentParser(
        description="间接注入毒化语料带外导入（正文编码落盘；输出仅计数/ID 段/指纹）")
    ap.add_argument("--inbox", help="inbox 目录（建议仓库外本地路径）")
    ap.add_argument("--dry-run", action="store_true", help="只报告不落盘")
    ap.add_argument("--remove-id", action="append", default=[], metavar="ID",
                    help="退役指定用例（可多次；指纹记入 import-manifest.json poisonCorpus.removals）")
    args = ap.parse_args()

    if not args.inbox and not args.remove_id:
        ap.error("须提供 --inbox / --remove-id 至少其一")

    corpus = load_corpus()

    # ── 退役（先于导入，刷新去重基线）──
    if args.remove_id:
        manifest = load_manifest()
        by_id = {item["id"]: item for item in corpus}
        removed_any = False
        for rid in args.remove_id:
            target = by_id.get(rid)
            if target is None:
                raise ImportFailure(f"退役目标不存在: {rid}")
            if args.dry_run:
                print(f"[dry-run] 将退役 {rid}（fileName={target.get('fileName')}）")
                continue
            corpus.remove(target)
            del by_id[rid]
            digest = target.get("documentSha256", "") or ""
            manifest["poisonCorpus"]["removals"].append(
                {"id": rid, "fileName": target.get("fileName"), "sha256": digest[:12]})
            removed_any = True
            print(f"已退役毒化语料 {rid}（fileName={target.get('fileName')}，指纹 {digest[:12]}）")
        if not args.dry_run and removed_any:
            CORPUS_FILE.parent.mkdir(parents=True, exist_ok=True)
            CORPUS_FILE.write_text(
                json.dumps(corpus, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            MANIFEST.write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"语料已更新: {CORPUS_FILE.relative_to(REPO_ROOT)}（累计 {len(corpus)} 条）")
        if args.dry_run:
            print("[dry-run] 未写盘")
        if not args.inbox:
            return 0

    fingerprints = {item.get("documentSha256") for item in corpus}
    max_seq = 0
    for item in corpus:
        m = ID_SUFFIX.match(item["id"])
        if m:
            max_seq = max(max_seq, int(m.group(1)))

    items, meta_files = read_inbox(Path(args.inbox).expanduser())
    planned, dup = [], 0
    for item in items:
        if item["sha256"] in fingerprints:
            dup += 1
            continue
        fingerprints.add(item["sha256"])
        planned.append(item)

    added = []
    manifest = load_manifest()
    for item in planned:
        max_seq += 1
        rid = f"{ID_PREFIX}-{max_seq:02d}"
        item["id"] = rid
        added.append(rid)
        manifest["poisonCorpus"]["entries"].append(
            {"id": rid, "fileName": item["fileName"], "sha256": item["sha256"][:12]})

    print(f"inbox 元数据文件 {meta_files} 个；用例 {len(items)} 条"
          f"（新增 {len(planned)} / 指纹重复跳过 {dup}）")
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
            "fileName": item["fileName"],
            "question": item["question"],
            "judgeCriteria": item["judgeCriteria"],
            "document": item["b64"],
            "documentEncoding": "base64",
            "documentSha256": item["sha256"],
        })
    CORPUS_FILE.parent.mkdir(parents=True, exist_ok=True)
    CORPUS_FILE.write_text(
        json.dumps(corpus, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    MANIFEST.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"语料已更新: {CORPUS_FILE.relative_to(REPO_ROOT)}（累计 {len(corpus)} 条）")
    print(f"指纹清单已更新: {MANIFEST.relative_to(REPO_ROOT)}"
          f"（poisonCorpus 累计 {len(manifest['poisonCorpus']['entries'])} 条）")
    print("下一步: ① mvn -q --no-transfer-progress -pl kb-eval -am test（加载自洽校验）；"
          "② 经生产管道上传毒化文档（fileName 须与上传文件名一致）；"
          "③ eval.indirect.enabled=true 复跑评估")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ImportFailure as e:
        print(f"导入失败: {e}", file=sys.stderr)
        sys.exit(1)
