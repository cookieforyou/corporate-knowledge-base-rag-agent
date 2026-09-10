# ============================================================
# kb-api 生产镜像（Phase 4 簇⑥ 4.11/4.12，v2.55）
#
# 构建形态（定案）：fat jar 宿主侧构建（mvn -DskipTests package）后
# docker build 打包——2 核 ECS 不做镜像内 Maven 全量构建（资源与
# 缓存面考量）。jar 路径经 JAR_FILE build-arg 覆盖。
#
# AppCDS 通道已退役（2026-09-10 生产实测证伪，坑位㊻ 19 章附录 E）：
# --enable-preview × 动态档案消费侧 SIGSEGV（训练可写档 197MB，
# 挂载启动即 ConstantPool::klass_at_impl 段错误崩溃循环）——
# 训练服务与 entrypoint 消费逻辑已移除，CDS 为秒级启动加速
# 非功能依赖，「失败即回落」设计预期兑现后整体退役。
#
# HEALTHCHECK 依赖 curl（temurin JRE 基础镜像无 curl，构建期安装）。
# ============================================================
FROM eclipse-temurin:21-jre-jammy

ARG JAR_FILE=kb-api/target/kb-api-1.0.0-SNAPSHOT.jar

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY ${JAR_FILE} /app/app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

# actuator/health 已 permitAll（SecurityConfig 白名单）
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8090/actuator/health | grep -q '"UP"' || exit 1

EXPOSE 8090

ENTRYPOINT ["/app/docker-entrypoint.sh"]
