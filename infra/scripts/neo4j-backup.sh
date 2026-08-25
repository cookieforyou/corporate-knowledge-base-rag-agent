#!/usr/bin/env bash
# ============================================================
# Neo4j 图数据定期备份（Phase 5 簇④ 批3）——灾备面扩图（17 章 §17.5）
#
# 形态：ECS 宿主侧执行（第二台 ECS 自托管 Community 实例）——
#   停服务 → neo4j-admin database dump → 本地 BACKUP_LOCAL_DIR
#   → mc 上传 MinIO kb-backups bucket（--md5 校验）→ 启服务
#   → 本地仅保留 BACKUP_LOCAL_RETAIN_DAYS 天。
# 双副本口径同 PG（MinIO 主副本 + 本地热缓存，灾备最小集扩展——
# 图数据可经回填任务再生，但再生需重付抽取费用，dump 为成本兜底）。
#
# 停备窗口说明：dump 窗口内图路检索降级为空路（三路融合退化双路，
# 降级矩阵 10.2 天然兜底）——RAG 主链全程可用，窗口秒级可接受。
# Community 版无在线备份（企业版权限），停备为官方支持形态。
#
# 用法：
#   bash infra/scripts/neo4j-backup.sh          # 手工单次执行
# cron 接线（每日 03:37，与 PG 03:07 错峰）：
#   37 3 * * * /opt/kb-rag-agent/infra/scripts/neo4j-backup.sh >/dev/null 2>&1
#
# 依赖：neo4j-admin（随实例安装）+ mc（MinIO Client，同 pg-backup.sh）。
# 服务管理：systemd（BACKUP_NEO4J_SERVICE）或 docker（BACKUP_NEO4J_CONTAINER
# 非空时走 docker stop/start 形态，二选一）。
#
# 配置：读 infra/.env 备份段（BACKUP_NEO4J_* 族 + 复用 MINIO_* 与
# BACKUP_LOCAL_DIR/BACKUP_LOCAL_RETAIN_DAYS）。逐项 grep 解析，
# 不 source 全文件（同 pg-backup.sh 纪律）。
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${ENV_FILE:-$SCRIPT_DIR/../.env}"

env_key() { # env_key <KEY> <缺省值>
  local v=""
  [[ -f "$ENV_FILE" ]] && v=$(grep -E "^${1}=" "$ENV_FILE" | tail -n1 | cut -d= -f2-)
  echo "${v:-$2}"
}

# ── 配置读取 ─────────────────────────────────
NEO4J_DATABASE=$(env_key BACKUP_NEO4J_DATABASE neo4j)
NEO4J_ADMIN_BIN=$(env_key BACKUP_NEO4J_ADMIN_BIN "")   # neo4j-admin 非 PATH 时指 bin 目录
NEO4J_SERVICE=$(env_key BACKUP_NEO4J_SERVICE neo4j)    # systemd 服务名
NEO4J_CONTAINER=$(env_key BACKUP_NEO4J_CONTAINER "")   # docker 容器名（非空优先走 docker）
MINIO_ENDPOINT=$(env_key BACKUP_MINIO_ENDPOINT http://127.0.0.1:9000)
MINIO_ALIAS=$(env_key BACKUP_MINIO_ALIAS kbbackup)
MINIO_BUCKET=$(env_key BACKUP_BUCKET kb-backups)
MINIO_ACCESS_KEY=$(env_key MINIO_ACCESS_KEY "")
MINIO_SECRET_KEY=$(env_key MINIO_SECRET_KEY "")
LOCAL_DIR=$(env_key BACKUP_LOCAL_DIR /opt/kb-rag-agent/backups)
RETAIN_DAYS=$(env_key BACKUP_LOCAL_RETAIN_DAYS 7)

[[ -n "$MINIO_ACCESS_KEY" && -n "$MINIO_SECRET_KEY" ]] || {
  echo "ERROR: MINIO_ACCESS_KEY/MINIO_SECRET_KEY 未配置（infra/.env）" >&2; exit 1; }
[[ -n "$NEO4J_ADMIN_BIN" ]] && export PATH="$NEO4J_ADMIN_BIN:$PATH"
command -v neo4j-admin >/dev/null || { echo "ERROR: neo4j-admin 不在 PATH（可设 BACKUP_NEO4J_ADMIN_BIN）" >&2; exit 1; }
command -v mc >/dev/null || { echo "ERROR: mc 未安装（MinIO Client，安装命令见 pg-backup.sh 头注）" >&2; exit 1; }

mkdir -p "$LOCAL_DIR"
LOG_FILE="$LOCAL_DIR/backup.log"
log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG_FILE"; }

# 日志轮转：超 1000 行截尾保留 500 行
if [[ -f "$LOG_FILE" && $(wc -l < "$LOG_FILE") -gt 1000 ]]; then
  tail -n 500 "$LOG_FILE" > "$LOG_FILE.tmp" && mv "$LOG_FILE.tmp" "$LOG_FILE"
fi

# ── 单实例锁（防 cron 与手工重入）─────────────
LOCK_FILE="$LOCAL_DIR/.neo4j-backup.lock"
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  log "SKIP: 另一备份进程持锁中，本次退出"
  exit 0
fi

STAMP=$(date '+%Y%m%d_%H%M%S')
DUMP_FILE="$LOCAL_DIR/neo4j_${NEO4J_DATABASE}_${STAMP}.dump"

stop_neo4j() {
  if [[ -n "$NEO4J_CONTAINER" ]]; then
    docker stop "$NEO4J_CONTAINER" >/dev/null
  else
    systemctl stop "$NEO4J_SERVICE"
  fi
}
start_neo4j() {
  if [[ -n "$NEO4J_CONTAINER" ]]; then
    docker start "$NEO4J_CONTAINER" >/dev/null
  else
    systemctl start "$NEO4J_SERVICE"
  fi
}

log "BEGIN: neo4j dump ${NEO4J_DATABASE} → ${DUMP_FILE}"
stop_neo4j
dump_ok=""
if neo4j-admin database dump "$NEO4J_DATABASE" --to-path="$LOCAL_DIR" 2>>"$LOG_FILE"; then
  dump_ok="yes"
fi
start_neo4j   # 无论 dump 成败，先恢复服务（停备窗口最小化）
[[ -n "$dump_ok" ]] || { log "ERROR: dump 失败（服务已恢复，详见 $LOG_FILE）"; exit 1; }

# neo4j-admin 产出文件名 = <database>.dump，归一为带时间戳命名
mv -f "$LOCAL_DIR/${NEO4J_DATABASE}.dump" "$DUMP_FILE"
[[ -s "$DUMP_FILE" ]] || { log "ERROR: 转储文件为空"; exit 1; }
LOCAL_SIZE=$(stat -c%s "$DUMP_FILE")
log "DUMP OK: ${LOCAL_SIZE} bytes（服务已恢复）"

# ── MinIO 上传（--md5 校验，同 PG 口径）────────
log "UPLOAD: mc → ${MINIO_ALIAS}/${MINIO_BUCKET}/neo4j_${NEO4J_DATABASE}_${STAMP}.dump"
mc alias set "$MINIO_ALIAS" "$MINIO_ENDPOINT" "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY" \
  --api S3v4 >/dev/null
mc mb --ignore-existing "$MINIO_ALIAS/$MINIO_BUCKET" >/dev/null
mc cp --md5 "$DUMP_FILE" "$MINIO_ALIAS/$MINIO_BUCKET/" >/dev/null
mc stat "$MINIO_ALIAS/$MINIO_BUCKET/neo4j_${NEO4J_DATABASE}_${STAMP}.dump" >/dev/null \
  || { log "ERROR: 上传后远端对象不存在"; exit 1; }
log "UPLOAD OK（--md5 校验通过 + 远端 stat 存在）"

# ── 本地保留策略 ─────────────────────────────
CLEANED=$(find "$LOCAL_DIR" -maxdepth 1 -name "neo4j_${NEO4J_DATABASE}_*.dump" \
  -mtime +"$RETAIN_DAYS" -print -delete | wc -l)
log "CLEAN: 本地清理 ${RETAIN_DAYS} 天前旧转储 ${CLEANED} 份"

log "SUCCESS: 图谱备份完成（本地 + MinIO 双副本）"
