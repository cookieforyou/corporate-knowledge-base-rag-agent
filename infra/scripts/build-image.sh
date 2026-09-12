#!/usr/bin/env bash
# ============================================================
# kb-api 镜像构建（Phase 4 簇⑥ 4.11，v2.55；2026-09-10 增 --no-mvn）
#
# 形态：宿主侧 mvn package（-DskipTests，测试归 CI/交付批）→
# docker build。tag 缺省 = v{semver}-{git短哈希}（禁 latest 纪律）。
# 输出 KB_IMAGE_TAG 供 compose 全栈入口（docker-compose.yml）消费：
#   bash infra/scripts/build-image.sh              # 完整：mvn package + docker build
#   bash infra/scripts/build-image.sh v1.0.0       # 显式 tag
#   bash infra/scripts/build-image.sh --no-mvn     # 跳过 mvn（部署包形态：jar 已在场，
#                                                  # ECS 上免 Maven；tag 取 DEPLOY_VERSION）
# 根 .dockerignore 白名单收窄 build context（Dockerfile/entrypoint/jar 三物）。
# ============================================================
set -euo pipefail

cd "$(dirname "$0")/../.."   # 仓库根（部署包形态 = 部署根，结构同构）

NO_MVN=0
if [ "${1:-}" = "--no-mvn" ]; then NO_MVN=1; shift; fi

# tag 解析优先级：显式参数 > DEPLOY_VERSION（部署包随附）> pom 版本 + git 短哈希
if [ -n "${1:-}" ]; then
    TAG="$1"
elif [ -f DEPLOY_VERSION ]; then
    TAG=$(cat DEPLOY_VERSION)
else
    VERSION=$(mvn -q -Dexec.executable=echo -Dexec.args='${project.version}' --non-recursive exec:exec 2>/dev/null || echo "1.0.0")
    GIT_SHORT=$(git rev-parse --short HEAD 2>/dev/null || echo "nogit")
    TAG="v${VERSION%%-SNAPSHOT}-$GIT_SHORT"
fi

if [ "$NO_MVN" -eq 0 ]; then
    echo "==> mvn package (skipTests)"
    mvn -q --no-transfer-progress -DskipTests package
fi

# fat jar 探测（repackage 产物；排除 .original 中间件）
JAR=$(ls kb-api/target/kb-api-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)
if [ -z "$JAR" ]; then
    echo "ERROR: kb-api/target/ 无 fat jar——先在本机 mvn -DskipTests package 并随部署包上传（docs/delivery/生产部署实操手册.md 阶段一）" >&2
    exit 1
fi
echo "==> jar: ${JAR}"

echo "==> docker build kb-rag-agent:${TAG}"
docker build -t "kb-rag-agent:${TAG}" .

echo ""
echo "KB_IMAGE_TAG=${TAG}"
echo "部署：infra/.env 内 KB_IMAGE_TAG=${TAG} → cd infra && docker compose up -d（全栈入口，新 tag 只重建 kb-api）"
