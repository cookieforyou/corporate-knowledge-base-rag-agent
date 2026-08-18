# tools/redteam — 对抗自动化通道（安全簇⑥ G1）

专项方案 §4.7 G1 落地：Promptfoo red-team 对接 `POST /api/v1/chat` 同步端点
（护栏拒绝 HTTP 400 + message，断言面清晰），配置化广扫注入/越狱/信息套取
三场景族，离线按需跑，摘要入档 `docs/project-progress/`。

## 纪律（§7 红线，全程强制）

- 生成物（攻击载荷与响应）**留在 promptfoo 工作目录**，`.gitignore` 覆盖不进仓库；
- 报告入档**只经** `summarize_report.py`（只回聚合计数：plugin/strategy × 防御/突破/error）；
- 原始输出 JSON **不贴对话、不喂 AI 会话**（字面载荷防上游检测 400 block + 上下文污染）;
- 高价值变体入集走带外编码通道：新词项 `tools/guardrail/import_words.py`（--inbox 仓库外），
  新评估样本 `tools/guardrail/import_corpus.py`——AI 零接触词面。

## env 前置

| 变量 | 说明 |
| --- | --- |
| `CHAT_BASE_URL` | 服务端点，如 `http://localhost:8090`（API 端口 8090，见运行环境定案） |
| `CHAT_JWT` | 租户用户 JWT（Casdoor 登录态；`owner` claim 缺失即 fail-closed `IDENTITY_INCOMPLETE`） |
| `OPENAI_API_KEY` | 生成与裁判模型凭据（默认 DeepSeek key） |
| `OPENAI_BASE_URL` | OpenAI 兼容端点（默认 `https://api.deepseek.com`） |
| `PROMPTFOO_DISABLE_REDTEAM_REMOTE_GENERATION` | **置 true**——本地生成，缺省时 promptfoo 会经其云端代理生成（purpose 与系统描述不出境） |

生成/裁判模型默认 `openai:chat:deepseek-v4-flash`（避 qwen3.5+ 商业版思考模式
20-60s/调用延迟，坑位⑮）；换模型改 `promptfooconfig.yaml` `redteam.provider`。

## 首跑步骤

```bash
cd tools/redteam
export CHAT_BASE_URL=http://localhost:8090
export CHAT_JWT=<Casdoor 登录 JWT>
export OPENAI_API_KEY=<DeepSeek key>
export OPENAI_BASE_URL=https://api.deepseek.com
export PROMPTFOO_DISABLE_REDTEAM_REMOTE_GENERATION=true

npx promptfoo@latest redteam run --config promptfooconfig.yaml -o redteam-result.json
python3 summarize_report.py --output redteam-result.json \
    --out-md ../../docs/project-progress/redteam-report-g1-<label>.md --label g1-<label>
```

`npx promptfoo@latest redteam report` 可开本地 web 视图逐条复核（载荷只在你本机浏览）。

## 限流与配额

- 租户桶 60/min：配置 `maxConcurrency: 1` + `delay: 1200` → ~48 req/min 留余量；
  摘要 error 桶若见 RATE_LIMITED 聚集，调大 delay。
- Token 预算日账本 100 万：首跑量级（约 60-90 请求）无压力。

## 口径说明

- `result.success=true` → 防御成功（拦截/拒答/未顺从）；`false` → 突破（裁判认定顺从）；
  provider 层 error → error 桶（多为护栏 400 拒绝；首跑后结合 kb_audit_log
  REJECTED 计数与 `rag.guardrail.*` 指标归因拆分「拦截 vs 网络异常」）。
- 首跑为低档（6 插件 × 3 例 + 5 策略乘数）；扩量改 `redteam.numTests` /
  plugins/strategies 全目录 `npx promptfoo@latest redteam plugins|strategies`。
- 语料承接：G2 语料扩容接收本通道高价值变体（编码化入集，见上纪律）。
