#!/usr/bin/env bash
# ============================================================
# kb-api 容器入口（Phase 4 簇⑥ 4.11，v2.55）
# AppCDS 动态消费：/opt/cds/app.jsa 存在则挂 SharedArchiveFile，
# 缺失（未训练/训练失败）回落普通启动——CDS 为启动加速优化，
# 非功能依赖。JAVA_OPTS 供调优注入（堆/虚拟线程参数等）。
# ============================================================
set -e

CDS_ARCHIVE=/opt/cds/app.jsa
CDS_OPTS=""
if [ -f "$CDS_ARCHIVE" ]; then
    CDS_OPTS="-XX:SharedArchiveFile=$CDS_ARCHIVE"
    echo "[entrypoint] AppCDS archive detected: $CDS_ARCHIVE"
else
    echo "[entrypoint] AppCDS archive absent, plain startup (run kb-api-cds-train to train)"
fi

exec java --enable-preview $CDS_OPTS $JAVA_OPTS -jar /app/app.jar
