#!/usr/bin/env bash
# ============================================================
# kb-api 镜像构建（Phase 4 簇⑥ 4.11，v2.55）
#
# 形态：宿主侧 mvn package（-DskipTests，测试归 CI/交付批）→
# docker build。tag 缺省 = v{semver}-{git短哈希}（禁 latest 纪律）。
# 输出 KB_IMAGE_TAG 供 docker-compose.app.yml 消费：
#   bash infra/scripts/build-image.sh          # 缺省 tag
#   bash infra/scripts/build-image.sh v1.0.0   # 显式 tag
# ============================================================
set -euo pipefail

cd "$(dirname "$0")/../.."   # 仓库根

VERSION=$(mvn -q -Dexec.executable=echo -Dexec.args='${project.version}' --non-recursive exec:exec 2>/dev/null || echo "1.0.0")
GIT_SHORT=$(git rev-parse --short HEAD 2>/dev/null || echo "nogit")
TAG="${1:-v${VERSION%%-SNAPSHOT}-$GIT_SHORT}"

echo "==> mvn package (skipTests)"
mvn -q --no-transfer-progress -DskipTests package

echo "==> docker build kb-rag-agent:${TAG}"
docker build -t "kb-rag-agent:${TAG}" .

echo ""
echo "KB_IMAGE_TAG=${TAG}"
echo "部署：infra/.env 内 KB_IMAGE_TAG=${TAG} → docker compose -f infra/docker-compose.app.yml up -d"
