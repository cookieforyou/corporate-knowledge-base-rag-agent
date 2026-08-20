# ============================================================
# kb-api 生产镜像（Phase 4 簇⑥ 4.11/4.12，v2.55）
#
# 构建形态（定案）：fat jar 宿主侧构建（mvn -DskipTests package）后
# docker build 打包——2 核 ECS 不做镜像内 Maven 全量构建（资源与
# 缓存面考量）。jar 路径经 JAR_FILE build-arg 覆盖。
#
# AppCDS（N5 顺带小件，零代码改动）：archive 于部署侧训练生成——
# compose `kb-api-cds-train` 服务（profiles: tools）以
# -XX:ArchiveClassesAtExit 训练跑（infra 可达，超时 SIGTERM 退出即
# 写档）落共享卷 /opt/cds/app.jsa；生产服务经 entrypoint 探测
# archive 存在与否动态挂载（缺失回落普通启动，零阻塞）。
# --enable-preview 与 CDS 兼容性经 ECS 训练跑实测（失败即回落）。
#
# HEALTHCHECK 依赖 curl（temurin JRE 基础镜像无 curl，构建期安装）。
# ============================================================
FROM eclipse-temurin:21-jre

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
