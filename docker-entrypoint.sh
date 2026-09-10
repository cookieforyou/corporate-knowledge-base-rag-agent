#!/usr/bin/env bash
# ============================================================
# kb-api 容器入口（Phase 4 簇⑥ 4.11，v2.55；2026-09-10 CDS 退役简化）
#
# AppCDS 消费逻辑移除（生产实测证伪，坑位㊻ 19 章附录 E）：
# --enable-preview × 动态 CDS 档案**消费侧 SIGSEGV**——训练侧可成功
# 写档（197MB written OK），但挂 -XX:SharedArchiveFile 启动后于
# Hibernate 初始化期 ConstantPool::klass_at_impl 段错误崩溃循环
# （连 error reporting 自身亦崩，hs_err 写不全；preview 字节码类
# 入档回放路径的 JVM 缺陷面，Temurin 21.0.11）。CDS 本为秒级启动
# 加速非功能依赖（Dockerfile 注记「失败即回落」设计预期兑现），
# 训练服务（compose tools profile）已同步移除，整体退役。
# JAVA_OPTS 供调优注入（堆/虚拟线程参数等）。
# ============================================================
set -e

exec java --enable-preview $JAVA_OPTS -jar /app/app.jar
