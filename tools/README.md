# tools/ — 运营与对抗工具

> 本目录收录**不进入运行时**的运维/安全/评测辅助脚本与配置。所有工具均以「内容盲」为纪律：输出只含计数、ID 段、指纹、族系名，不回显词面或攻击载荷。

| 子目录 | 内容 | 入口 |
|---|---|---|
| [`guardrail/`](./guardrail/) | 词表编码/导入、覆盖复算、热重载信号、L2 漏判复核、G5 部署核查 | [README](./guardrail/README.md) |
| [`redteam/`](./redteam/) | Promptfoo 红队两阶段执行（generate → 预分析 → eval）+ 报告聚合 | [README](./redteam/README.md) |
| [`other/`](./other/) | 外部协议互操作脚本（A2A 官方 SDK client 等） | 见目录内脚本头注 |

**相关文档**：安全专项 [方案](../docs/project-optimization/安全加固专项优化方案（调研实证版）.md) §7 · 设计 [12 章](../docs/project-implement/12-安全护栏体系.md) · 进度 [06 卷](../docs/project-progress/06-安全加固专项（成簇推进）.md) · 待执行 [清单](../docs/project-progress/用户侧待执行项清单.md)。
