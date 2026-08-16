#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
安全簇① T4 —— 词表带外导入脚本（用户本地执行，AI 零接触词面）

从 inbox 目录读取词项（JSONL/CSV），逐条 Base64 编码后并入结构化词表
（kb-commons/src/main/resources/guardrail/{injection,output}-rules.yml），
并追加指纹清单 tools/guardrail/import-manifest.json（仅元数据，无词值）。

内容纪律（第七节条 2/4）：
  - 词值只被本脚本程序化读取/编码/写盘；stdout 仅回显
    计数 / 新增 ID 段 / 族系分布 / SHA-256 指纹前缀 / 带行号错误，绝不回显词值。
  - inbox 建议放仓库外本地路径（明文永不进仓库）；导入完成后 inbox 自行处置。

inbox 文件格式（每文件逐条，文件间合并；.jsonl 与 .csv 均支持）：

  JSONL 每行一个对象：
    {"value": "...", "side": "injection|output",
     "family": "七分法/三分类枚举名（可选，缺省 UNCLASSIFIED）",
     "lang": "zh|en|...（可选）", "type": "KEYWORD|REGEX（可选，缺省 KEYWORD）",
     "action": "BLOCK|FLAG（可选，缺省 BLOCK）",
     "encoding": "base64（可选——value 已是 Base64 编码态时声明，脚本校验后原样落盘）"}

  CSV 首行表头（列序固定）：value,side,family,lang,type,action
    （CSV 不支持 encoding 列，值一律明文由脚本编码）

用法：
  python3 tools/guardrail/import_words.py --inbox <目录> [--dry-run]
         [--id-prefix-injection import-inj] [--id-prefix-output import-out]
         [--remove-id <词项ID>]...（退役指定词项，可多次；可与导入同批或单独执行）

幂等：按「解码值 + side + type」的 SHA-256 与既有词表及本批已收词项去重，
重复运行不产生重复词项。REGEX 词项落盘前经 Python re 预编译校验
（Java Pattern 语法近似，编译失败拒绝该行）。
"""

import argparse
import base64
import csv
import hashlib
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
RULES_FILES = {
    "injection": REPO_ROOT / "kb-commons/src/main/resources/guardrail/injection-rules.yml",
    "output": REPO_ROOT / "kb-commons/src/main/resources/guardrail/output-rules.yml",
}
MANIFEST = REPO_ROOT / "tools/guardrail/import-manifest.json"

INJECTION_FAMILIES = {
    "INSTRUCTION_OVERRIDE", "ROLE_HIJACK", "INFO_EXTRACTION", "ENCODING_OBFUSCATION",
    "MULTILINGUAL", "JAILBREAK", "TOOL_INDUCED", "UNCLASSIFIED",
}
OUTPUT_FAMILIES = {
    "BUSINESS_CONFIDENTIAL", "COMPLIANCE_SENSITIVE", "COMPETITOR_COMPARISON", "UNCLASSIFIED",
}
SIDES = {"injection": INJECTION_FAMILIES, "output": OUTPUT_FAMILIES}
TYPES = {"KEYWORD", "REGEX"}
ACTIONS = {"BLOCK", "FLAG"}

ENTRY_HEAD = re.compile(r"^- id:\s*(\S+)\s*$")
ID_SPLIT = re.compile(r"^(.*?)-?(\d+)$")


class ImportFailure(Exception):
    """带定位的导入错误（消息仅含结构信息，不含词值）"""


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


# ── 既有词表解析（结构提取：词项块 / 指纹集 / 前缀最大序号）──

def parse_existing(path: Path):
    """返回 (词项[(id, base64值)], 指纹集{(sha, 文件名)}, 前缀→最大序号)"""
    if not path.exists():
        raise ImportFailure(f"词表文件不存在: {path}")
    lines = path.read_text(encoding="utf-8").splitlines()
    entries = []
    current_id, current_lines = None, []
    for line in lines:
        m = ENTRY_HEAD.match(line)
        if m:
            if current_id is not None:
                entries.append((current_id, current_lines))
            current_id, current_lines = m.group(1), [line]
        elif current_id is not None:
            current_lines.append(line)
    if current_id is not None:
        entries.append((current_id, current_lines))

    parsed, fingerprints, max_suffix = [], set(), {}
    for entry_id, block in entries:
        b64 = None
        for line in block:
            if line.strip().startswith("value:"):
                b64 = line.split(":", 1)[1].strip().strip('"').strip("'")
                break
        if b64 is None:
            raise ImportFailure(f"词项 {entry_id} 缺 value 字段（{path.name}）")
        try:
            decoded = base64.b64decode(b64, validate=True).decode("utf-8")
        except Exception:
            raise ImportFailure(f"词项 {entry_id} 既有 Base64 解码失败（{path.name}）")
        fingerprints.add((sha256_hex(decoded), path.name))
        m = ID_SPLIT.match(entry_id)
        if m:
            prefix = m.group(1).rstrip("-")
            max_suffix[prefix] = max(max_suffix.get(prefix, 0), int(m.group(2)))
        parsed.append((entry_id, b64))
    return parsed, fingerprints, max_suffix


# ── inbox 读取 ──

def read_inbox(inbox_dir: Path):
    """读取 inbox 全部词项；返回 (items, file_count)。item 含解码值指纹与编码态，不回显。"""
    if not inbox_dir.is_dir():
        raise ImportFailure(f"inbox 目录不存在: {inbox_dir}")
    items, file_count = [], 0
    for p in sorted(inbox_dir.iterdir()):
        if p.suffix == ".jsonl":
            for lineno, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
                if not line.strip():
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError as e:
                    raise ImportFailure(f"{p.name}:{lineno} JSON 解析失败（{e.msg} 列 {e.colno}）")
                items.append(normalize_item(obj, f"{p.name}:{lineno}"))
            file_count += 1
        elif p.suffix == ".csv":
            with p.open(encoding="utf-8", newline="") as fh:
                reader = csv.reader(fh)
                header = next(reader, None)
                if header is None or [h.strip().lower() for h in header[:6]] != \
                        ["value", "side", "family", "lang", "type", "action"]:
                    raise ImportFailure(f"{p.name}: CSV 表头须为 value,side,family,lang,type,action")
                for lineno, row in enumerate(reader, 2):
                    if not row or not "".join(row).strip():
                        continue
                    if len(row) < 6:
                        raise ImportFailure(f"{p.name}:{lineno} 列数不足 6")
                    value, side, family, lang, rtype, action = (c.strip() for c in row[:6])
                    items.append(normalize_item(
                        {"value": value, "side": side, "family": family,
                         "lang": lang, "type": rtype, "action": action},
                        f"{p.name}:{lineno}"))
            file_count += 1
    return items, file_count


def normalize_item(obj: dict, loc: str) -> dict:
    value = str(obj.get("value", "")).strip()
    if not value:
        raise ImportFailure(f"{loc} value 为空")
    side = str(obj.get("side", "")).strip().lower()
    if side not in SIDES:
        raise ImportFailure(f"{loc} side 非法（须 injection|output）")
    family = str(obj.get("family", "") or "UNCLASSIFIED").strip().upper()
    if family not in SIDES[side]:
        raise ImportFailure(f"{loc} family 不属于 {side} 侧合法族系集")
    rtype = str(obj.get("type", "") or "KEYWORD").strip().upper()
    if rtype not in TYPES:
        raise ImportFailure(f"{loc} type 非法（须 KEYWORD|REGEX）")
    action = str(obj.get("action", "") or "BLOCK").strip().upper()
    if action not in ACTIONS:
        raise ImportFailure(f"{loc} action 非法（须 BLOCK|FLAG）")
    lang = str(obj.get("lang", "") or "").strip().lower()
    encoding = str(obj.get("encoding", "") or "").strip().lower()
    if encoding not in ("", "base64"):
        raise ImportFailure(f"{loc} encoding 仅支持 base64 或省略")

    if encoding == "base64":
        try:
            decoded = base64.b64decode(value, validate=True).decode("utf-8")
        except Exception:
            raise ImportFailure(f"{loc} 声明 encoding=base64 但值非合法 Base64 编码")
        b64 = value
    else:
        decoded = value
        b64 = base64.b64encode(value.encode("utf-8")).decode("ascii")
    if rtype == "REGEX":
        try:
            re.compile(decoded)
        except re.error as e:
            raise ImportFailure(f"{loc} REGEX 模式编译失败（{e.msg} 位 {e.pos}）")
    return {"side": side, "family": family, "lang": lang, "type": rtype,
            "action": action, "b64": b64, "sha256": sha256_hex(decoded),
            "char_len": len(decoded)}


# ── 写入 ──

def render_entry(rule_id: str, item: dict) -> str:
    return "\n".join([
        f"- id: {rule_id}",
        f"  family: {item['family']}",
        f"  lang: {item['lang']}",
        f"  type: {item['type']}",
        f"  value: \"{item['b64']}\"",
        f"  action: {item['action']}",
        "  enabled: true",
    ]) + "\n"


def append_entries(path: Path, rendered: list):
    text = path.read_text(encoding="utf-8")
    if not text.endswith("\n"):
        text += "\n"
    path.write_text(text + "".join(rendered), encoding="utf-8")


def remove_entry(path: Path, rule_id: str) -> str:
    """按 id 移除词项块；返回被移除值的 base64（供指纹记录，不回显）"""
    lines = path.read_text(encoding="utf-8").splitlines()
    start = next((i for i, l in enumerate(lines)
                  if ENTRY_HEAD.match(l) and ENTRY_HEAD.match(l).group(1) == rule_id), None)
    if start is None:
        raise ImportFailure(f"退役目标不存在: {rule_id}（{path.name}）")
    end = next((i for i in range(start + 1, len(lines)) if ENTRY_HEAD.match(lines[i])), len(lines))
    b64 = None
    for line in lines[start:end]:
        if line.strip().startswith("value:"):
            b64 = line.split(":", 1)[1].strip().strip('"').strip("'")
    del lines[start:end]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return b64 or ""


def load_manifest() -> dict:
    if MANIFEST.exists():
        return json.loads(MANIFEST.read_text(encoding="utf-8"))
    return {"description": "安全簇① T4 带外导入指纹清单（仅元数据，无词值）",
            "entries": [], "removals": []}


def main() -> int:
    ap = argparse.ArgumentParser(description="词表带外导入（编码并库；输出仅计数/ID 段/指纹）")
    ap.add_argument("--inbox", help="inbox 目录（建议仓库外本地路径）")
    ap.add_argument("--dry-run", action="store_true", help="只报告不落盘")
    ap.add_argument("--id-prefix-injection", default="import-inj")
    ap.add_argument("--id-prefix-output", default="import-out")
    ap.add_argument("--remove-id", action="append", default=[], metavar="ID",
                    help="退役指定词项（可多次）")
    args = ap.parse_args()

    if not args.inbox and not args.remove_id:
        ap.error("须提供 --inbox 或 --remove-id 至少其一")

    manifest = load_manifest()
    prefixes = {"injection": args.id_prefix_injection, "output": args.id_prefix_output}
    removed_any = False

    # ── 退役（先于导入，刷新后再做指纹去重基线）──
    if args.remove_id:
        for rid in args.remove_id:
            side = next((s for s in SIDES
                         if any(eid == rid for eid, _ in parse_existing(RULES_FILES[s])[0])), None)
            if side is None:
                raise ImportFailure(f"退役目标不存在于任一词表: {rid}")
            if args.dry_run:
                print(f"[dry-run] 将退役 {rid}（{side}-rules.yml）")
                continue
            b64 = remove_entry(RULES_FILES[side], rid)
            digest = sha256_hex(base64.b64decode(b64).decode("utf-8")) if b64 else ""
            manifest["removals"].append({"id": rid, "side": side, "sha256": digest[:12]})
            removed_any = True
            print(f"已退役词项 {rid}（{side}-rules.yml，指纹 {digest[:12]}）")

    # ── 导入 ──
    if args.inbox:
        fingerprints, max_suffix = {}, {}
        for side in SIDES:
            _, fingerprints[side], max_suffix[side] = parse_existing(RULES_FILES[side])

        items, file_count = read_inbox(Path(args.inbox).expanduser())
        planned, dup = {"injection": [], "output": []}, 0
        for item in items:
            key = (item["sha256"], RULES_FILES[item["side"]].name)
            if key in fingerprints[item["side"]]:
                dup += 1
                continue
            fingerprints[item["side"]].add(key)
            planned[item["side"]].append(item)

        added, rendered = [], {"injection": [], "output": []}
        for side in ("injection", "output"):
            if not planned[side]:
                continue
            prefix_key = prefixes[side].rstrip("-")
            seq = max_suffix[side].get(prefix_key, 0) + 1
            for item in planned[side]:
                rid = f"{prefixes[side]}-{seq:02d}"
                seq += 1
                rendered[side].append(render_entry(rid, item))
                manifest["entries"].append({
                    "id": rid, "side": side, "family": item["family"], "lang": item["lang"],
                    "type": item["type"], "action": item["action"],
                    "sha256": item["sha256"], "char_len": item["char_len"]})
                added.append(rid)
        if not args.dry_run:
            for side in ("injection", "output"):
                if rendered[side]:
                    append_entries(RULES_FILES[side], rendered[side])

        fam_dist = {}
        for item in items:
            fam_dist[item["family"]] = fam_dist.get(item["family"], 0) + 1
        print(f"inbox 文件 {file_count} 个；词项 {len(items)} 条"
              f"（新增 {len(added)} / 指纹重复跳过 {dup}）")
        for side in ("injection", "output"):
            if planned[side]:
                print(f"  {side}: 新增 {len(planned[side])} 条（前缀 {prefixes[side]}）")
        if added:
            print(f"新增 ID 段: {added[0]} .. {added[-1]}")
        if fam_dist:
            print("族系分布: " + ", ".join(f"{k}={v}" for k, v in sorted(fam_dist.items())))
        if args.dry_run:
            print("[dry-run] 未写盘")
            return 0
        if added or removed_any:
            MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                                encoding="utf-8")
            print(f"指纹清单已更新: {MANIFEST.relative_to(REPO_ROOT)}"
                  f"（累计导入 {len(manifest['entries'])} / 退役 {len(manifest['removals'])}）")
    elif removed_any:
        # 纯退役运行同样落清单（修复：早期版本仅导入分支写清单）
        MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8")
        print(f"指纹清单已更新: {MANIFEST.relative_to(REPO_ROOT)}"
              f"（累计导入 {len(manifest['entries'])} / 退役 {len(manifest['removals'])}）")

    if not args.dry_run and (args.remove_id or args.inbox):
        # 落盘后结构自检：重解析两份词表，输出计数（不回显内容）
        for side in SIDES:
            parsed, _, _ = parse_existing(RULES_FILES[side])
            print(f"自检 {side}-rules.yml: {len(parsed)} 条词项，全部 Base64 可解码")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ImportFailure as e:
        print(f"导入失败: {e}", file=sys.stderr)
        sys.exit(1)
