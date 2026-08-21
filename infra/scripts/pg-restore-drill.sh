#!/usr/bin/env bash
# ============================================================
# PG 备份恢复演练（Phase 4 簇⑥ 批3，v2.57）
#
# 形态：备份恢复至独立演练库 ${PG_DATABASE}_drill（不触生产库）→
# 表集比对 + 逐表行数比对 → 可选 API 抽检。演练后 drill 库保留
# 供人工抽查，下次演练自动 dropdb 重建。
#
# 判定口径：
#   MATCH  演练行数 == 生产行数（安静系统严格口径）
#   LAG    演练行数 < 生产行数（备份后生产新增行；非严格模式容忍）
#   FAIL   演练行数 > 生产行数（不应发生）或演练表缺失生产行非空
#   --strict：全部 MATCH 方通过（安静系统演练口径，E2E 验收用）
#
# 用法：
#   bash infra/scripts/pg-restore-drill.sh                 # 缺省取本地最新 .dump
#   bash infra/scripts/pg-restore-drill.sh <dump文件路径>
#   bash infra/scripts/pg-restore-drill.sh --from-minio    # 从 MinIO 拉最新对象
#   bash infra/scripts/pg-restore-drill.sh --strict        # 严格行数一致
#   CHAT_BASE_URL=http://127.0.0.1:8090 CHAT_JWT=<令牌> \
#     bash infra/scripts/pg-restore-drill.sh --api-check   # 附加 API 抽检
#
# 权限注记：转储含 CREATE EXTENSION IF NOT EXISTS vector——演练库已装
# 该扩展时空操作，否则需超级用户。脚本以 DB 凭据尝试预装，失败仅告警
# （随后 pg_restore 报权限错时，以 postgres 超级用户对演练库补装后重跑）。
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${ENV_FILE:-$SCRIPT_DIR/../.env}"

env_key() { # env_key <KEY> <缺省值>
  local v=""
  [[ -f "$ENV_FILE" ]] && v=$(grep -E "^${1}=" "$ENV_FILE" | tail -n1 | cut -d= -f2-)
  echo "${v:-$2}"
}

# ── 配置读取（与 pg-backup.sh 同口径）──────────
PG_HOST=$(env_key BACKUP_PG_HOST 127.0.0.1)
PG_PORT=$(env_key BACKUP_PG_PORT 5432)
PG_DATABASE=$(env_key BACKUP_PG_DATABASE kb_rag_agent)
PG_USER=$(env_key DB_USERNAME "")
PG_PASSWORD=$(env_key DB_PASSWORD "")
PG_BIN=$(env_key BACKUP_PG_BIN "")
PG_EXTENSIONS=$(env_key BACKUP_PG_EXTENSIONS vector)
MINIO_ENDPOINT=$(env_key BACKUP_MINIO_ENDPOINT http://127.0.0.1:9000)
MINIO_ALIAS=$(env_key BACKUP_MINIO_ALIAS kbbackup)
MINIO_BUCKET=$(env_key BACKUP_BUCKET kb-backups)
MINIO_ACCESS_KEY=$(env_key MINIO_ACCESS_KEY "")
MINIO_SECRET_KEY=$(env_key MINIO_SECRET_KEY "")
LOCAL_DIR=$(env_key BACKUP_LOCAL_DIR /opt/kb-rag-agent/backups)

DRILL_DB="${PG_DATABASE}_drill"
STRICT=0
FROM_MINIO=0
API_CHECK=0
DUMP_FILE=""

for arg in "$@"; do
  case "$arg" in
    --strict)     STRICT=1 ;;
    --from-minio) FROM_MINIO=1 ;;
    --api-check)  API_CHECK=1 ;;
    -h|--help)    grep '^#' "$0" | head -30; exit 0 ;;
    *)            DUMP_FILE="$arg" ;;
  esac
done

[[ -n "$PG_USER" && -n "$PG_PASSWORD" ]] || {
  echo "ERROR: DB_USERNAME/DB_PASSWORD 未配置（infra/.env）" >&2; exit 1; }
[[ -n "$PG_BIN" ]] && export PATH="$PG_BIN:$PATH"
for tool in pg_restore psql dropdb createdb; do
  command -v "$tool" >/dev/null || { echo "ERROR: $tool 不在 PATH（可设 BACKUP_PG_BIN）" >&2; exit 1; }
done

psql_at() { # psql_at <dbname> <SQL>——单值回显（ON_ERROR_STOP：SQL 错即非零退出）
  PGPASSWORD="$PG_PASSWORD" psql -v ON_ERROR_STOP=1 \
    -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$1" -Atc "$2"
}

# ── 定位转储文件 ─────────────────────────────
if [[ -n "$DUMP_FILE" ]]; then
  [[ -f "$DUMP_FILE" ]] || { echo "ERROR: 转储文件不存在：$DUMP_FILE" >&2; exit 1; }
elif [[ "$FROM_MINIO" -eq 1 ]]; then
  command -v mc >/dev/null || { echo "ERROR: --from-minio 需 mc（安装见 pg-backup.sh 头注）" >&2; exit 1; }
  mc alias set "$MINIO_ALIAS" "$MINIO_ENDPOINT" "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY" \
    --api S3v4 >/dev/null
  LATEST=$(mc ls "$MINIO_ALIAS/$MINIO_BUCKET/" | awk '{print $NF}' \
    | grep "^${PG_DATABASE}_.*\.dump$" | sort | tail -n1)
  [[ -n "$LATEST" ]] || { echo "ERROR: MinIO ${MINIO_BUCKET} 无 ${PG_DATABASE}_*.dump 对象" >&2; exit 1; }
  mkdir -p "$LOCAL_DIR"
  DUMP_FILE="$LOCAL_DIR/$LATEST"
  echo "==> mc 拉回 $LATEST"
  mc cp "$MINIO_ALIAS/$MINIO_BUCKET/$LATEST" "$DUMP_FILE" >/dev/null
else
  DUMP_FILE=$(find "$LOCAL_DIR" -maxdepth 1 -name "${PG_DATABASE}_*.dump" \
    | sort | tail -n1 || true)
  [[ -n "$DUMP_FILE" ]] || {
    echo "ERROR: 本地无 .dump（先跑 pg-backup.sh，或 --from-minio）" >&2; exit 1; }
fi
echo "==> 演练转储：$DUMP_FILE"

# ── 重建演练库 ───────────────────────────────
export PGPASSWORD="$PG_PASSWORD"
echo "==> 重建演练库 $DRILL_DB"
dropdb -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" --if-exists "$DRILL_DB"
createdb -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" "$DRILL_DB"

for ext in $PG_EXTENSIONS; do
  if ! psql_at "$DRILL_DB" "CREATE EXTENSION IF NOT EXISTS ${ext};" >/dev/null 2>&1; then
    echo "WARN: 扩展 ${ext} 预装失败（非超级用户）——pg_restore 若报权限错，"
    echo "      以 postgres 超级用户执行："
    echo "      psql -d ${DRILL_DB} -c 'CREATE EXTENSION IF NOT EXISTS ${ext};'"
  fi
done

echo "==> pg_restore"
pg_restore -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$DRILL_DB" \
  --no-owner --no-privileges --exit-on-error "$DUMP_FILE"

# ── 表集与行数比对 ───────────────────────────
PROD_TABLES=$(psql_at "$PG_DATABASE" \
  "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY 1")
DRILL_TABLES=$(psql_at "$DRILL_DB" \
  "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY 1")

FAIL=0; MATCH=0; LAG=0; WARN=0
printf '%-24s %12s %12s %s\n' "表" "生产行数" "演练行数" "判定"
printf '%s\n' "------------------------------------------------------------"

for t in $PROD_TABLES; do
  if ! grep -qx "$t" <<<"$DRILL_TABLES"; then
    printf '%-24s %12s %12s %s\n' "$t" \
      "$(psql_at "$PG_DATABASE" "SELECT count(*) FROM $t")" "-" "WARN 演练库缺表"
    WARN=$((WARN+1)); continue
  fi
  P=$(psql_at "$PG_DATABASE" "SELECT count(*) FROM $t")
  D=$(psql_at "$DRILL_DB" "SELECT count(*) FROM $t")
  if [[ "$P" == "$D" ]]; then
    printf '%-24s %12s %12s %s\n' "$t" "$P" "$D" "MATCH"
    MATCH=$((MATCH+1))
  elif [[ "$D" -lt "$P" ]]; then
    printf '%-24s %12s %12s %s\n' "$t" "$P" "$D" "LAG（备份后新增）"
    LAG=$((LAG+1))
    [[ "$STRICT" -eq 1 ]] && FAIL=$((FAIL+1))
  else
    printf '%-24s %12s %12s %s\n' "$t" "$P" "$D" "FAIL（演练>生产）"
    FAIL=$((FAIL+1))
  fi
done

for t in $DRILL_TABLES; do
  grep -qx "$t" <<<"$PROD_TABLES" || {
    echo "WARN: 演练库含生产已不存在的表 $t（备份跨 schema 变更期）"
    WARN=$((WARN+1)); }
done

# ── 可选 API 抽检 ────────────────────────────
if [[ "$API_CHECK" -eq 1 ]]; then
  : "${CHAT_BASE_URL:?--api-check 需 CHAT_BASE_URL（如 http://127.0.0.1:8090）}"
  : "${CHAT_JWT:?--api-check 需 CHAT_JWT（Bearer 令牌）}"
  CODE=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $CHAT_JWT" \
    "$CHAT_BASE_URL/api/v1/stats/overview")
  if [[ "$CODE" == "200" ]]; then
    echo "API 抽检：GET /api/v1/stats/overview → 200 OK"
  else
    echo "API 抽检 FAIL：GET /api/v1/stats/overview → $CODE"
    FAIL=$((FAIL+1))
  fi
fi

# ── 汇总 ─────────────────────────────────────
echo "------------------------------------------------------------"
echo "汇总：MATCH ${MATCH} / LAG ${LAG} / FAIL ${FAIL} / WARN ${WARN}" \
  "$([[ "$STRICT" -eq 1 ]] && echo '（--strict 严格口径）')"
if [[ "$FAIL" -gt 0 ]]; then
  echo "RESULT: FAIL——存在行数逆序或缺表（strict 模式 LAG 亦判失败）"
  exit 1
fi
echo "RESULT: PASS——恢复演练通过"
