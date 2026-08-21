#!/usr/bin/env bash
# ============================================================
# PG 定期备份（Phase 4 簇⑥ 批3，v2.57）
#
# 形态：ECS 宿主侧执行（PG 原生安装）——
#   pg_dump -Fc → 本地 BACKUP_LOCAL_DIR → mc 上传 MinIO kb-backups
#   bucket（--md5 校验）→ 本地仅保留 BACKUP_LOCAL_RETAIN_DAYS 天。
# 双副本口径：MinIO 为主副本（灾备恢复演练可从 MinIO 拉回），
# 本地为热缓存（恢复演练缺省消费本地最新 .dump）。
#
# 灾备最小集边界：PG 为唯一事实源（Chunk/审计/会话全量），
# ES/Milvus 可经重建通道再生（簇③ ReindexGateway），
# MinIO 原件另议 OSS 冷备（可选项，未排期）——故 PG 备份即最小充分集。
#
# 用法：
#   bash infra/scripts/pg-backup.sh          # 手工单次执行
# cron 接线（每日 03:07，错峰整点）：
#   7 3 * * * /opt/kb-rag-agent/infra/scripts/pg-backup.sh >/dev/null 2>&1
#   （脚本内部自写 backup.log，cron 侧静默即可）
#
# 依赖：pg_dump（PG 原生安装自带）+ mc（MinIO Client）。mc 安装：
#   curl -fsSL https://dl.min.io/client/mc/release/linux-amd64/mc \
#     -o /usr/local/bin/mc && chmod +x /usr/local/bin/mc
#
# 配置：读 infra/.env 备份段（BACKUP_* 族 + 复用 DB_USERNAME/DB_PASSWORD
# 与 MINIO_ACCESS_KEY/MINIO_SECRET_KEY）。**逐项 grep 解析，不 source
# 全文件**——.env 含 JAVA_OPTS=-Xms1g -Xmx2g 未引号多词值，source 即报错。
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
PG_HOST=$(env_key BACKUP_PG_HOST 127.0.0.1)
PG_PORT=$(env_key BACKUP_PG_PORT 5432)
PG_DATABASE=$(env_key BACKUP_PG_DATABASE kb_rag_agent)
PG_USER=$(env_key DB_USERNAME "")
PG_PASSWORD=$(env_key DB_PASSWORD "")
PG_BIN=$(env_key BACKUP_PG_BIN "")            # pg_dump 非 PATH 时指 bin 目录
MINIO_ENDPOINT=$(env_key BACKUP_MINIO_ENDPOINT http://127.0.0.1:9000)
MINIO_ALIAS=$(env_key BACKUP_MINIO_ALIAS kbbackup)
MINIO_BUCKET=$(env_key BACKUP_BUCKET kb-backups)
MINIO_ACCESS_KEY=$(env_key MINIO_ACCESS_KEY "")
MINIO_SECRET_KEY=$(env_key MINIO_SECRET_KEY "")
LOCAL_DIR=$(env_key BACKUP_LOCAL_DIR /opt/kb-rag-agent/backups)
RETAIN_DAYS=$(env_key BACKUP_LOCAL_RETAIN_DAYS 7)

[[ -n "$PG_USER" && -n "$PG_PASSWORD" ]] || {
  echo "ERROR: DB_USERNAME/DB_PASSWORD 未配置（infra/.env）" >&2; exit 1; }
[[ -n "$MINIO_ACCESS_KEY" && -n "$MINIO_SECRET_KEY" ]] || {
  echo "ERROR: MINIO_ACCESS_KEY/MINIO_SECRET_KEY 未配置（infra/.env）" >&2; exit 1; }
[[ -n "$PG_BIN" ]] && export PATH="$PG_BIN:$PATH"
command -v pg_dump >/dev/null || { echo "ERROR: pg_dump 不在 PATH（可设 BACKUP_PG_BIN）" >&2; exit 1; }
command -v mc >/dev/null || { echo "ERROR: mc 未安装（MinIO Client，安装命令见脚本头注）" >&2; exit 1; }

mkdir -p "$LOCAL_DIR"
LOG_FILE="$LOCAL_DIR/backup.log"
log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG_FILE"; }

# 日志轮转：超 1000 行截尾保留 500 行
if [[ -f "$LOG_FILE" && $(wc -l < "$LOG_FILE") -gt 1000 ]]; then
  tail -n 500 "$LOG_FILE" > "$LOG_FILE.tmp" && mv "$LOG_FILE.tmp" "$LOG_FILE"
fi

# ── 单实例锁（防 cron 与手工重入）─────────────
LOCK_FILE="$LOCAL_DIR/.pg-backup.lock"
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  log "SKIP: 另一备份进程持锁中，本次退出"
  exit 0
fi

STAMP=$(date '+%Y%m%d_%H%M%S')
DUMP_FILE="$LOCAL_DIR/${PG_DATABASE}_${STAMP}.dump"

log "BEGIN: pg_dump ${PG_DATABASE}@${PG_HOST}:${PG_PORT} → ${DUMP_FILE}"
PGPASSWORD="$PG_PASSWORD" pg_dump -Fc \
  -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DATABASE" \
  -f "$DUMP_FILE"
[[ -s "$DUMP_FILE" ]] || { log "ERROR: 转储文件为空"; exit 1; }
LOCAL_SIZE=$(stat -c%s "$DUMP_FILE")
log "DUMP OK: ${LOCAL_SIZE} bytes"

# ── MinIO 上传（--md5 校验）───────────────────
log "UPLOAD: mc → ${MINIO_ALIAS}/${MINIO_BUCKET}/${PG_DATABASE}_${STAMP}.dump"
mc alias set "$MINIO_ALIAS" "$MINIO_ENDPOINT" "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY" \
  --api S3v4 >/dev/null
mc mb --ignore-existing "$MINIO_ALIAS/$MINIO_BUCKET" >/dev/null
mc cp --md5 "$DUMP_FILE" "$MINIO_ALIAS/$MINIO_BUCKET/" >/dev/null
mc stat "$MINIO_ALIAS/$MINIO_BUCKET/${PG_DATABASE}_${STAMP}.dump" >/dev/null \
  || { log "ERROR: 上传后远端对象不存在"; exit 1; }
log "UPLOAD OK（--md5 校验通过 + 远端 stat 存在）"

# ── 本地保留策略 ─────────────────────────────
CLEANED=$(find "$LOCAL_DIR" -maxdepth 1 -name "${PG_DATABASE}_*.dump" \
  -mtime +"$RETAIN_DAYS" -print -delete | wc -l)
log "CLEAN: 本地清理 ${RETAIN_DAYS} 天前旧转储 ${CLEANED} 份"

log "SUCCESS: 备份完成（本地 + MinIO 双副本）"
