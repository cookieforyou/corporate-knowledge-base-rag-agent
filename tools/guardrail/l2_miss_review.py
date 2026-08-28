#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
簇② 批5 处置 c —— L2 漏判语料带外复核脚本（用户本地执行，AI 零接触内容）

批5-b 定谳（2026-08-28）：判据措辞杠杆实证见顶（17 靶点漏判毛命中 1 例被
超时 1 例对冲，净零；13 PASS「judge 本不见攻击意图」例零突破）→ 矛盾收归
样本 × 门禁契约面。本脚本为 c（语料双向校验）的用户侧执行通道：逐例解码
展示批5-b 判定的 20 例 L2 漏判样本（15 PASS + 5 SUSPECT），采集处置裁决
落台账；重建样本经重编码模式回写。

内容纪律（第七节，与 import_corpus.py 同款）：
  - 样本内容只在用户终端会话展示（脚本解码直显），程序化写盘面仅
    ID / 族系 / 指纹前缀 / 处置枚举 / 时间戳——台账与 stdout 绝不落内容。
  - 台账文件（l2-miss-review-ledger.json）可安全回传 AI 链路（纯元数据）。

处置判据（三分法，详用户侧清单批5-c 段）：
  A = KEEP        保留——剥壳后攻击意图明确且 expected BLOCK 标签正当；
                  样本留在 L2 门禁子集，治理面归 e（门禁契约）
  B = DEMOTE      降观察集——弱攻击 / 标签存疑 / 消息级良性；attackType 变更
                  出 L2 门禁分母（载体随复核结果议定，脚本只记裁决不落变更）
  C = REBUILD     重建——意图明确但措辞/结构缺陷致 judge 不可剥壳；用户改写
                  后经 --reencode 回写（同 id 原地替换 + 指纹更新）
  S = SKIP        暂缓（可后续续审，review 再跑会重新拾起）

用法：
  # 逐例复核（可中断续跑——已裁决例自动跳过）
  python3 tools/guardrail/l2_miss_review.py review

  # 只列台账状态（纯元数据，无内容）
  python3 tools/guardrail/l2_miss_review.py list

  # 重建回写：新载荷明文写入仓库外文件后
  python3 tools/guardrail/l2_miss_review.py reencode --id inj-jailbreak-NN --from-file <明文文件>

复核后必须运行 `mvn -q --no-transfer-progress -pl kb-eval -am test` 复跑
GoldenDatasetLoaderTest（语料自洽契约单一事实源在测试侧）。
"""

import argparse
import base64
import hashlib
import json
import sys
from datetime import datetime
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CORPUS_FILE = REPO_ROOT / "kb-eval/src/main/resources/golden/injection-qa.json"
LEDGER_FILE = REPO_ROOT / "tools/guardrail/l2-miss-review-ledger.json"

DISPOSITIONS = {
    "A": "KEEP（保留——标签正当，治理归 e 门禁契约）",
    "B": "DEMOTE（降观察集——弱攻击/标签存疑，出 L2 门禁分母）",
    "C": "REBUILD（重建——意图明确而结构缺陷，改写后经 reencode 回写）",
    "S": "SKIP（暂缓，续审再跑拾起）",
}


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def load_corpus():
    if not CORPUS_FILE.exists():
        raise FileNotFoundError(f"语料文件不存在: {CORPUS_FILE}")
    return json.loads(CORPUS_FILE.read_text(encoding="utf-8"))


def load_ledger():
    if not LEDGER_FILE.exists():
        raise FileNotFoundError(f"台账不存在: {LEDGER_FILE}")
    return json.loads(LEDGER_FILE.read_text(encoding="utf-8"))


def save_ledger(ledger) -> None:
    LEDGER_FILE.write_text(
        json.dumps(ledger, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def cmd_list(ledger) -> int:
    entries = ledger["entries"]
    done = sum(1 for e in entries if e["disposition"] not in (None, "", "S"))
    print(f"台账 {len(entries)} 例（已裁决 {done} / 暂缓 {sum(1 for e in entries if e['disposition'] == 'S')}"
          f" / 待审 {sum(1 for e in entries if e['disposition'] in (None, ''))}）")
    for e in entries:
        disp = e["disposition"] or "—"
        note = f"（{e['dispositionNote']}）" if e.get("dispositionNote") else ""
        print(f"  {e['id']:<22} {e['family']:<14} batch5b={e['batch5b']:<8} "
              f"裁决={disp}{note}")
    return 0


def cmd_review(ledger) -> int:
    corpus = {item["id"]: item for item in load_corpus()}
    entries = ledger["entries"]
    pending = [e for e in entries if e["disposition"] in (None, "", "S")]
    if not pending:
        print("全部已裁决，无待审样本。")
        return 0
    print(f"待审 {len(pending)} 例（共 {len(entries)}）。"
          f"裁决键: {'/'.join(DISPOSITIONS)}；Q=中止保存进度。\n")
    for i, e in enumerate(pending, 1):
        item = corpus.get(e["id"])
        if item is None:
            print(f"[{i}/{len(pending)}] {e['id']}：语料中不存在，跳过（台账保留待查）\n")
            continue
        raw = item["question"]
        if item.get("questionEncoding") == "base64":
            try:
                text = base64.b64decode(raw, validate=True).decode("utf-8")
            except Exception:
                print(f"[{i}/{len(pending)}] {e['id']}：编码解析失败，跳过\n")
                continue
        else:
            text = raw
        print("=" * 72)
        print(f"[{i}/{len(pending)}] {e['id']}   族系={e['family']}   "
              f"batch5b 裁决={e['batch5b']}")
        print(f"裁决史: {e['verdictHistory']}")
        print("-" * 72)
        print(text)
        print("-" * 72)
        while True:
            choice = input(f"处置 [{'/'.join(DISPOSITIONS)} / Q 中止]: ").strip().upper()
            if choice == "Q":
                save_ledger(ledger)
                print("进度已保存，续跑即从未决例拾起。")
                return 0
            if choice in DISPOSITIONS:
                break
            print("  无效键，请重试。")
        note = input("备注（可空，只记结构性描述勿抄内容）: ").strip()
        e["disposition"] = choice
        e["dispositionNote"] = note
        e["reviewedAt"] = now_iso()
        print(f"  已记 {e['id']} = {choice} {DISPOSITIONS[choice]}\n")
    save_ledger(ledger)
    done = sum(1 for e in entries if e["disposition"] not in (None, "", "S"))
    print(f"本轮复核完成；台账累计裁决 {done}/{len(entries)}。"
          f"台账为纯元数据，可直接回传。")
    return 0


def cmd_reencode(ledger, target_id: str, from_file: Path) -> int:
    if not from_file.is_file():
        print(f"明文文件不存在: {from_file}", file=sys.stderr)
        return 1
    plaintext = from_file.read_text(encoding="utf-8").strip()
    if not plaintext:
        print("明文文件为空", file=sys.stderr)
        return 1
    corpus = load_corpus()
    target = next((item for item in corpus if item["id"] == target_id), None)
    if target is None:
        print(f"语料中不存在: {target_id}", file=sys.stderr)
        return 1
    old_sha = (target.get("questionSha256") or "")[:12]
    new_b64 = base64.b64encode(plaintext.encode("utf-8")).decode("ascii")
    new_sha = sha256_hex(plaintext)
    target["question"] = new_b64
    target["questionEncoding"] = "base64"
    target["questionSha256"] = new_sha
    CORPUS_FILE.write_text(
        json.dumps(corpus, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    entry = next((e for e in ledger["entries"] if e["id"] == target_id), None)
    if entry is not None:
        entry["disposition"] = "C"
        entry["reencodedAt"] = now_iso()
        entry["rebuildTrail"] = {"sha256From": old_sha, "sha256To": new_sha[:12]}
        save_ledger(ledger)
    print(f"已重建 {target_id}（指纹 {old_sha} → {new_sha[:12]}，内容未回显）")
    print("下一步: mvn -q --no-transfer-progress -pl kb-eval -am test"
          "（语料自洽契约校验——单一事实源）")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(
        description="L2 漏判语料带外复核（内容仅用户终端可见；台账纯元数据）")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("review", help="逐例解码展示并采集处置裁决（可中断续跑）")
    sub.add_parser("list", help="只列台账状态（纯元数据）")
    rp = sub.add_parser("reencode", help="重建样本回写（新载荷明文文件 → 原地编码替换）")
    rp.add_argument("--id", required=True, help="重建目标样本 ID")
    rp.add_argument("--from-file", required=True, help="新载荷明文文件（建议仓库外路径）")
    args = ap.parse_args()

    ledger = load_ledger()
    if args.cmd == "list":
        return cmd_list(ledger)
    if args.cmd == "review":
        return cmd_review(ledger)
    return cmd_reencode(ledger, args.id, Path(args.from_file).expanduser())


if __name__ == "__main__":
    sys.exit(main())
