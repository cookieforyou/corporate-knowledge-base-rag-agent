# tools/guardrail — 词表与对抗语料运营工具箱

> **定位**：安全簇①/④/⑥ 的**带外**运营通道——词表与攻击语料逐条 Base64 编码存储，脚本本体零字面载荷，stdout 只回计数/ID 段/指纹/族系分布。所有脚本纯 Python 标准库（无需第三方依赖），在仓库根执行。
>
> **红线**：任何脚本输出、文档、会话都不得出现字面攻击载荷；inbox 放仓库外；详细纪律见 `docs/project-optimization/安全加固专项优化方案（调研实证版）.md` §7。

## 1. 脚本索引

| 脚本 | 用途 | 典型命令 |
|---|---|---|
| `encode_rules.py` | 存量字面词表/语料迁移为编码态（一次性迁移） | `python3 tools/guardrail/encode_rules.py` |
| `import_words.py` | 词表带外导入（file 侧档案 + manifest 幂等） | `python3 tools/guardrail/import_words.py --inbox <仓库外目录> [--dry-run]` |
| `import_corpus.py` | 注入语料带外导入/退役（Base64 + SHA-256） | `python3 tools/guardrail/import_corpus.py --inbox <目录> [--remove-id <ID>]` |
| `import_poison_corpus.py` | 间接注入毒化语料带外导入 | `python3 tools/guardrail/import_poison_corpus.py --inbox <目录>` |
| `probe_match.py` | 单条消息词表匹配复算（内容盲） | `python3 tools/guardrail/probe_match.py --text <文本> --side injection` |
| `probe_corpus_coverage.py` | 语料四分区覆盖复算（BLOCK/KEYWORD/TRIGGER/SILENT） | `python3 tools/guardrail/probe_corpus_coverage.py --corpus <path> --side injection` |
| `probe_redteam_coverage.py` | Promptfoo 预生成用例四分区复算 | `python3 tools/guardrail/probe_redteam_coverage.py --tests test-cases.yaml` |
| `publish_reload_signal.py` | 发布词表热重载 Redis 信号（三通道回落） | `python3 tools/guardrail/publish_reload_signal.py` |
| `l2_miss_review.py` | L2 漏判样本带外复核/重建（批5-c） | `python3 tools/guardrail/l2_miss_review.py --help` |
| `g5_e2e_check.py` | G5 重载链路部署后核查（步骤 1/1'/3/6 三态） | `python3 tools/guardrail/g5_e2e_check.py [--skip-external]` |

**数据文件**：`word-escalation-candidates.json`（G3 升格候选台账）、`import-manifest.json`（导入指纹）、`migration-manifest.json`（迁移留痕）、`l2-miss-review-ledger.json`（漏判复核台账）。

## 2. 两条运营路径

### 2.1 在线单条（生产 `RAG_GUARDRAIL_RULES_SOURCE=db`）

前端运维中心第五 Tab 或 `/api/v1/admin/guardrail/rules` API：新词缺省 **FLAG** 观察 → 命中计数/审计 `/drill` → 零误伤后升 **BLOCK** → 自动 reload + 编码 YAML 存档。**写路径仅 db 态可用**；file 态写请求返回 400 `GUARDRAIL_RULE_SOURCE_READONLY`。

### 2.2 带外成批（Git Ops / 回滚阀门）

`import_words.py` 改 file 侧编码词表 → git commit → 同步外部 `file:` 路径（整文件覆盖语义）→ `publish_reload_signal.py` 或等待 mtime 轮询（60s）。DB 态下运行时仍以 DB 为事实源，file 档需经 CRUD/Seeder/重启同步；**回滚阀门** = `RAG_GUARDRAIL_RULES_SOURCE=file` 重启。

## 3. 当前基线（2026-10）

- 词表规模：注入侧 174（bundled 173 + `api-inj-1`）/ 输出侧 65（DB 单轨数；file 档为 173/65）。
- 语料四分区：**56 / 2 / 28 / 41**（G4 升格后；压制象限 11→2）。
- 干净集：140 例 BLOCK 零命中；注入语料 127 条（分类 44/12/26/26/19）。
- G5 重载链路：本地×生产数据面六步全 PASS；生产库已还原；ECS 重启为可选遗留。

## 4. 相关文档

- 设计：[12 章 安全护栏体系](../../docs/project-implement/12-安全护栏体系.md) §12.7/§12.11
- 进度：[06 卷 安全加固专项](../../docs/project-progress/06-安全加固专项（成簇推进）.md)
- 清单：[用户侧待执行项清单](../../docs/project-progress/用户侧待执行项清单.md)（G1-G5）
- 红队：[tools/redteam/README.md](../redteam/README.md)
