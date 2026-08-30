# tools/redteam — 对抗自动化通道（安全簇⑥ G1）

专项方案 §4.7 G1 落地：Promptfoo red-team 对接 `POST /api/v1/chat` 同步端点
（护栏拒绝 HTTP 400 + message，断言面清晰），配置化广扫注入/越狱/信息套取
三场景族，离线按需跑，摘要入档 `docs/project-progress/`。

## 纪律（§7 红线，全程强制）

- 生成物（攻击载荷与响应）**留在 promptfoo 工作目录**，`.gitignore` 覆盖不进仓库
  （含 `test-cases*.yaml/json` 预生成用例文件——两阶段执行形态下同样含字面载荷）；
- 报告入档**只经** `summarize_report.py`（只回聚合计数：plugin/strategy × 防御/突破/error）；
- 原始输出 JSON **不贴对话、不喂 AI 会话**（字面载荷防上游检测 400 block + 上下文污染）;
- 高价值变体入集走带外编码通道：新词项 `tools/guardrail/import_words.py`（--inbox 仓库外），
  新评估样本 `tools/guardrail/import_corpus.py`——AI 零接触词面。

## 口径定案（2026-08-29，12 章 §12.11 / 16 章契约）

- **G1 = 端到端防御广度读数**：测四层防线联合效果（L1 词表拦截 / L2 二判拦截 /
  主模型对齐拒答 / 输出护栏），「防御成功」桶天然混杂四层机制，单看三桶无法归因
  到具体防线。
- **G1 读数不直接映射 eval L2 门禁阈值**：eval L2 门禁经力判键旁路触发启发式、
  测的是 L2 判别力；G1 生产链无键、无 sessionId（跨轮信号结构性归零），大量生成
  用例落静默区（词表全档零命中，防御归主模型对齐）。阈值校准经
  「G1 发现高价值变体 → 带外入库 → eval 力判复跑」链路，非直读。
- **MULTILINGUAL 族代表性登记**：multilingual 策略实证不支持（Invalid strategy(s)），
  G1 不覆盖多语种攻击面——L2 门禁防域含 MULTILINGUAL，其阈值校准不由 G1 承接，
  继续沿用批5-d 语料复跑口径。

## env 前置

| 变量 | 说明 |
| --- | --- |
| `CHAT_BASE_URL` | 服务端点，如 `http://localhost:8090`（API 端口 8090，见运行环境定案） |
| `CHAT_JWT` | 租户用户 JWT（Casdoor 登录态；`owner` claim 缺失即 fail-closed `IDENTITY_INCOMPLETE`） |
| `OPENAI_API_KEY` | 生成与裁判模型凭据（默认 DeepSeek key） |
| `OPENAI_BASE_URL` | OpenAI 兼容端点（默认 `https://api.deepseek.com`） |
| `PROMPTFOO_DISABLE_REDTEAM_REMOTE_GENERATION` | **置 true**——本地生成，缺省时 promptfoo 会经其云端代理生成（purpose 与系统描述不出境） |
| `PROMPTFOO_NUM_JAILBREAK_ITERATIONS` | 调整 JAILBREAK 迭代次数，减少次数能降低成本、加快测试，但可能牺牲攻击覆盖率；增加次数则能更深入探索，但成本和时间也更高 |

生成/裁判模型默认 `openai:chat:deepseek-v4-flash`（避 qwen3.5+ 商业版思考模式
20-60s/调用延迟，坑位⑮）；换模型改 `promptfooconfig.yaml` `redteam.provider`。

## 首跑步骤（两阶段执行形态，2026-08-29 定案）

```bash
cd tools/redteam
export CHAT_BASE_URL=http://localhost:8090
export CHAT_JWT=<Casdoor 登录 JWT>
export OPENAI_API_KEY=<DeepSeek key>
export OPENAI_BASE_URL=https://api.deepseek.com
export PROMPTFOO_DISABLE_REDTEAM_REMOTE_GENERATION=true
export PROMPTFOO_NUM_JAILBREAK_ITERATIONS=5

# ── 阶段一：生成测试用例（不执行）──
npx promptfoo@latest redteam generate -c promptfooconfig.yaml -o test-cases.yaml

# ── 阶段 1.5（执行前预分析，内容盲）：四分区复算 ──
# 量化生成用例的词表命中形态——BLOCK 直拦 / KEYWORD 压制 / TRIGGER 可触发
# （=生产链下到达 L2 的分区）/ SILENT 静默区；只回用例 ID 与命中词项 ID。
# 读数口径（2026-08-30 形态定谳后）：四分区仅计「确定性载荷」用例（base 与
# 逐字编码策略）；迭代式策略（jailbreak:meta / provider
# promptfoo:redteam:iterative:*）载荷运行时构造，探针单列「迭代式种子态」
# 计数不入四分区，其防御归因走阶段二运行时（审计 + rag.guardrail.l2.triggered）。
python3 ../guardrail/probe_redteam_coverage.py --tests test-cases.yaml

# ── 阶段二：直跑预生成用例（不重新生成）──
npx promptfoo@latest redteam eval -c test-cases.yaml -j 1 -o redteam-result.json

# ── 报告聚合（唯一入档通道，内容盲只回计数）──
python3 summarize_report.py --output redteam-result.json \
    --out-md ../../docs/reports/redteam/redteam-report-g1-<label>.md --label g1-<label>
```

- 两阶段依据：`redteam run` 为生成+运行合体形态；`redteam generate -o` 产物为
  完整配置（含 targets/tests），`redteam eval -c <产物>` 直跑不再重新生成
  （官方 examples 同款）。`-j 1` 钉并发与配置 `maxConcurrency: 1` 一致。
- 复跑纪律：同一份 `test-cases.yaml` 可多次执行（读数对照面）；重生成即换用例集，
  对照面重建。
- （可选）逐条复核：`npx promptfoo@latest redteam eval -c test-cases.yaml` 后
  `npx promptfoo@latest redteam report` 开本地 web 视图（载荷只在你本机浏览）。

## 限流与配额

- 租户桶 60/min：配置 `maxConcurrency: 1` + `delay: 1200` → ~48 req/min 留余量；
  摘要 error 桶若见 RATE_LIMITED 聚集，调大 delay。
- Token 预算日账本 100 万：首跑量级（约 60-90 请求）无压力。

## 口径说明

- `result.success=true` → 防御成功（拦截/拒答/未顺从）；`false` → 突破（裁判认定顺从）；
  provider 层 error → error 桶（多为护栏 400 拒绝；首跑后结合 kb_audit_log
  REJECTED 计数与 `rag.guardrail.*` 指标归因拆分「拦截层 × 网络异常」）。
- 首跑为低档（6 插件 × 3 例 + 4 策略乘数）；扩量改 `redteam.numTests` /
  plugins/strategies 全目录 `npx promptfoo@latest redteam plugins|strategies`。
- 语料承接：G2 语料扩容接收本通道高价值变体（编码化入集，见上纪律）——
  进过 L2 且被误判的 → 复现批5-c 路径；是攻击但从未进 L2（静默区）的 →
  词表覆盖面缺口归 G2 带外扩词，非 L2-judge 问题。
