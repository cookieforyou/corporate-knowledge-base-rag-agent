# Gatling 压测报告（簇⑥ 4.12，v2.59）

> **状态**：模板——机器侧资产已交付（kb-loadtest 四场景 + 生成桩，2026-08-22），ECS 实测待用户执行（清单 LT1），数据回填本文档与 18 §18.4 实测列。
>
> **口径**：ECS 2 核单机基线；QPS 口径已废止（v2.29 复审），验收线 = 18 §18.4 四场景阈值；方法论 = 15 §15.4。压测期间监控栈建议降采样防资源争抢失真。

## 1. 环境

| 项 | 值 |
|---|---|
| 执行日期 | 待回填 |
| kb-api 形态 | 容器（docker-compose.app.yml）/ fat jar（择一） |
| kb-api 版本（KB_IMAGE_TAG 或 commit） | 待回填 |
| JVM 参数（JAVA_OPTS） | 待回填 |
| 向量库 provider | milvus / pgvector |
| Gatling 版本 | 3.15.1（gatling-maven-plugin 4.21.10） |
| 压测机 | ECS 本机（推荐）/ 异地（注明，P95 含跨网 RTT） |
| 限流/预算前置 | RAG_RATELIMIT_ENABLED=___ RAG_TOKEN_BUDGET_ENABLED=___（压测后已恢复 ✓/✗） |

## 2. 场景结果

### 2.1 SmokeProbe 连通探针（先决）

- 结果：⬜ 通过 / ⬜ 失败（失败原因：___）

### 2.2 场景 A 检索真压（验收线：P95 < 500ms + 零失败）

| 项 | 值 |
|---|---|
| 注入 | ___ rps × ___ s |
| 请求总数 / 失败数 | ___ / ___ |
| P50 / P95 / P99（ms） | ___ / ___ / ___ |
| 断言 | ⬜ 通过 / ⬜ 未达 |
| 超线归因（latencyMs 分段，如适用） | rewrite ___ ms / retrieval ___ ms / rerank ___ ms |

### 2.3 场景 B 生成桩压（验收线：50 并发零失败）

| 项 | 值 |
|---|---|
| 注入 | ___ 并发 × ___ s（桩参数 tokens=___ interval=___ms） |
| 对话总数 / 失败数 | ___ / ___ |
| 全对话时延 P50 / P95（ms） | ___ / ___ |
| JVM 峰值内存 / 线程数 | ___ / ___ |
| 断言 | ⬜ 通过 / ⬜ 未达 |

### 2.4 场景 D SSE 长会话（验收线：20 并发零中断 + 全 DONE）

| 项 | 值 |
|---|---|
| 形态 | 桩形态 / 真实模型形态 |
| 注入 | ___ 并发 × ___ s |
| 对话总数 / 失败数 | ___ / ___ |
| ERROR 帧数 | ___ |
| 断言 | ⬜ 通过 / ⬜ 未达 |

### 2.5 场景 C 真实 LLM 小样本（验收线：TTFT P95 < 2s + TPOT < 100ms）

| 项 | 值 |
|---|---|
| 执行确认 | 计费确认 ✓（日期：___） |
| 样本数 | ___（缺省 10） |
| TTFT 样本值 / P95（ms） | ___ / ___ |
| TPOT 逐样本（控制台输出，ms） | ___ |
| 断言 | ⬜ 通过 / ⬜ 未达 |

## 3. 观测佐证

- Grafana 截图：kb-rag-overview（压测期）——待附
- Grafana 截图：kb-rag-supplier-sla（桩压期应全零为正确态；真实模型形态见降级占比）——待附
- Gatling HTML 报告归档路径：kb-loadtest/target/gatling/___

## 4. 瓶颈分析与结论

（待回填：达标情况综述 / 瓶颈段定位 / 阈值修订建议 / 后续优化项登记）

## 5. 恢复确认

- infra/.env 还原（DEEPSEEK_BASE_URL / RAG_ROUTING_FALLBACK_ENABLED / 限流预算两项）：✓/✗
- 重建后冒烟对话正常：✓/✗
- Prometheus targets 双 UP：✓/✗
