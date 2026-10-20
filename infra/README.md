# infra — 部署与运维资产

> 生产形态：**ECS 单机（2C/8G）+ 第二台 ECS（Neo4j 可选）**。应用与监控全栈经 `docker-compose.yml`（include 两分栈）一键起停；宿主原生 PG、宿主 nginx 托管前端。逐步操作见 [生产部署实操手册](../docs/delivery/生产部署实操手册.md)，高层口径见 [17 章](../docs/project-implement/17-部署与运维.md)。

## 1. 资产索引

| 资产 | 用途 |
|---|---|
| `docker-compose.yml` | **全栈唯一入口**（include app + monitoring 两分栈；compose ≥v2.20）；`docker compose up -d` 一键全栈 |
| `docker-compose.app.yml` | kb-api 单栈：镜像 tag `:?` 守卫、healthcheck、restart、日志轮转、内存限额、词表存档卷 |
| `docker-compose.monitoring.yml` | Prometheus / Grafana / Jaeger / node-exporter 四服务（端口 9092/3001/16687/4319，避开共存栈） |
| `kb-stack.service` | systemd oneshot 全栈开机自启（单 unit；旧 app/monitoring 双 unit 已退役） |
| `.env.example` | 生产 env 模板（连接/模型/开关/备份/CORS/WS/Neo4j/钉钉/A2A）；实体 `.env` 不入 git，0600 |
| `nginx/frontend.conf` | 宿主 nginx：前端静态 + `/api` `/ws` 同源反代 8090；SSE/WS/上传四坑位 |
| `prometheus/prometheus.yml` | 抓取配置（kb-rag-agent / node-exporter） |
| `prometheus/alert-rules-kb-rag.yml` | 14 条告警规则 + 自检矩阵 |
| `grafana/` | 五面板 provisioning（overview / retrieval / llm / business / supplier-sla） |
| `scripts/build-image.sh` | 宿主构建镜像（`--no-mvn` 用于本机打包上传形态；tag = semver-git 短哈希） |
| `scripts/pg-backup.sh` | PG `pg_dump -Fc` → 本地 + MinIO 双副本（md5、flock、7 天保留、cron 03:07） |
| `scripts/pg-restore-drill.sh` | 独立演练库恢复 + 表集/行数 MATCH/LAG/FAIL（`--strict` / `--from-minio` / `--api-check`） |
| `scripts/neo4j-backup.sh` | Neo4j 停备 dump → 本地 + MinIO（cron 03:37，与 PG 错峰） |

## 2. 常用命令

```bash
cd infra
cp .env.example .env && chmod 600 .env     # 填实际值
docker compose config                      # 校验（含 :? 守卫）
docker compose up -d                       # 全栈起
docker compose ps / logs -f kb-api
docker compose down                        # 停（卷保留；分栈 -f 勿加 --remove-orphans）
```

**升级**：本机 `mvn -DskipTests package` → 组包上传 → `bash infra/scripts/build-image.sh --no-mvn` → 更新 `.env` 的 `KB_IMAGE_TAG` → `docker compose up -d`。回退 = tag 改回旧值 `up -d`。

**前端**：`cd frontend && npm run build` → `dist/` 部署到 nginx root；`frontend/.env` 的 `VITE_AUTH_*` 构建期内联，换机必带。

## 3. 关键纪律

- **禁 latest**：`KB_IMAGE_TAG` / 监控镜像均钉版；本地缓存 latest 可能多年未更新（坑位㊼）。
- **全栈唯一形态**：日常命令无 `-f`；分栈 `-f` 仅「只停单面」例外，且**勿加 `--remove-orphans`**（同 project 会互删容器）。
- **备份**：PG 是唯一事实源；ES/向量可经重建再生；Neo4j 图可回填再生（需重付抽取成本）。恢复演练每月或 schema 变更前。
- **G5 词表重载**：v2.112-v2.114 为代码面变更，部署需重建镜像；重启后 `python3 tools/guardrail/g5_e2e_check.py` 复核。
- **安全组**：对外只留前端/Grafana 双域名反代 + 应用层认证；API/监控/基础设施端口零公网放行。

## 4. 相关文档

- [运维手册](../docs/delivery/运维手册.md) · [生产部署实操手册](../docs/delivery/生产部署实操手册.md)
- [99.5% 兜底自检清单](../docs/project-operations/99.5-兜底自检清单.md)
- [17 章 部署与运维](../docs/project-implement/17-部署与运维.md) · [13 章 可观测性](../docs/project-implement/13-可观测性体系.md)
- [用户侧待执行项清单](../docs/project-progress/用户侧待执行项清单.md)（F1-F3/M1/DR1/SG1/LT1/G5）
