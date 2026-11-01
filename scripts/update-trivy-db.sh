#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd "${script_dir}/.." && pwd)"

cd "${project_dir}"

container_name="${CODESENTRY_SERVER_CONTAINER:-codesentry-server}"
if [[ "$(docker inspect -f '{{.State.Running}}' "${container_name}" 2>/dev/null || true)" != "true" ]]; then
  echo "CodeSentry 后端容器 ${container_name} 未运行，请先启动后端。" >&2
  exit 1
fi

docker exec -i "${container_name}" sh -lc '
set -eu
cache_dir="${TRIVY_CACHE_DIR:-/root/.cache/trivy}"
timeout="${CODESENTRY_TRIVY_DB_UPDATE_TIMEOUT:-30m}"

trivy rootfs \
  --cache-dir "${cache_dir}" \
  --db-repository public.ecr.aws/aquasecurity/trivy-db:2 \
  --db-repository ghcr.io/aquasecurity/trivy-db:2 \
  --download-db-only \
  --timeout "${timeout}" \
  --no-progress

trivy rootfs \
  --cache-dir "${cache_dir}" \
  --java-db-repository public.ecr.aws/aquasecurity/trivy-java-db:1 \
  --java-db-repository ghcr.io/aquasecurity/trivy-java-db:1 \
  --download-java-db-only \
  --timeout "${timeout}" \
  --no-progress

echo "Trivy 漏洞库和 Java 索引库更新完成。"
'
