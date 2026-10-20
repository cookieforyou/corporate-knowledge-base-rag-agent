# docs/ — 文档总索引

> 项目全阶段收官（2026-09-16）+ 收官后增量（2026-10）后的文档导航。**设计唯一依据** = `project-implement/`；**过程与 E2E** = `project-progress/`；**对外交付** = `delivery/`；**未来演进** = `project-future/`。根 [README](../README.md) 是项目门面。

## 1. 按用途导航

| 目录 | 内容 | 入口 |
|---|---|---|
| [`project-implement/`](./project-implement/) | 19 章设计实现报告（战略/需求/架构/实现/质量）+ 附录 A-E | [设计索引](./project-implement/README.md) · [§18.6/§18.7 验收矩阵与增量](./project-implement/18-交付验收标准.md) |
| [`project-implement/archive/`](./project-implement/archive/) | v1 合订本（2026-07-27，历史底稿，非当前设计依据） | [归档原文](./project-implement/archive/) |
| [`project-progress/`](./project-progress/) | 阶段/专项/子簇推进记录、每日状态、用户侧待执行项 | [进度索引](./project-progress/项目阶段推进任务清单完成记录.md) · [用户侧清单](./project-progress/用户侧待执行项清单.md) |
| [`project-optimization/`](./project-optimization/) | Phase 1-3 复盘、Phase 4/5 复审方案、安全专项方案、行业痛点调研 | [Phase 5 复审方案](./project-optimization/Phase%205%20复审与规划方案（调研实证版）.md) |
| [`project-future/`](./project-future/) | 下一阶段演进预案（工具链/协议/模型/检索/规模化） | [未来扩展索引](./project-future/README.md) |
| [`delivery/`](./delivery/) | 运维手册、部署实操、API 文档、用户手册、MCP/A2A 集成指南 | [交付索引](./delivery/README.md) |
| [`reports/`](./reports/) | 压测报告、红队报告、供应链扫描/SBOM | [压测报告](./reports/loadtest/Gatling压测报告（Phase4簇⑥4.12）.md) · [红队报告](./reports/redteam/redteam-report-g1-2026-08-31.md) |
| [`project-operations/`](./project-operations/) | 99.5% 可用性兜底自检清单（4.11 验收件） | [自检清单](./project-operations/99.5-兜底自检清单.md) |
| [`corpus/`](./corpus/) | 演示/评估语料（企业制度、K8s、发票、产品、DDD、DocMind 介绍） | — |

## 2. 推荐阅读顺序

1. **新读者**：根 [README](../README.md) → [设计索引](./project-implement/README.md) → [18 章 §18.6/§18.7](./project-implement/18-交付验收标准.md) → [进度索引](./project-progress/项目阶段推进任务清单完成记录.md)
2. **集成方**：[API 文档](./delivery/API文档.md) → [MCP 集成指南](./delivery/MCP集成指南.md) / [A2A 集成指南](./delivery/A2A集成指南.md)
3. **运维方**：[运维手册](./delivery/运维手册.md) → [生产部署实操手册](./delivery/生产部署实操手册.md) → [99.5% 自检](./project-operations/99.5-兜底自检清单.md)
4. **二次开发**：根 [CLAUDE.md](../CLAUDE.md) → [6 章 模块结构](./project-implement/06-Maven多模块工程结构.md) → [模块 README](../kb-eval/README.md) / [前端 README](../frontend/README.md) / [infra README](../infra/README.md) / [tools README](../tools/README.md)

## 3. 文档纪律

- 设计修正先回写 `project-implement/`（版本递增 + 修订注记），再更新 `project-progress/` 任务行与根 `CLAUDE.md`。
- 用户侧动作只登记在 [用户侧待执行项清单](./project-progress/用户侧待执行项清单.md)（唯一源）。
- 过程细节入进度卷，架构事实入 CLAUDE.md（≤24KB）；两份索引负责导航，不重复正文。
- 敏感词纪律：任何文档不得出现字面攻击载荷，只以族系名/结构描述/样本 ID 表达。
