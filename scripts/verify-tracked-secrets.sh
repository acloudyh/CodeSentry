#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export_dir=$(mktemp -d "${TMPDIR:-/tmp}/codesentry-secrets.XXXXXX")
trap 'rm -rf "$export_dir"' EXIT
# 仅导出 Git 索引文件；不读取运行目录、用户扫描产物、字体草稿或秘密文件。
git checkout-index --all --prefix="$export_dir/"
if command -v gitleaks >/dev/null 2>&1; then
  gitleaks dir --config "$export_dir/.gitleaks.toml" --redact --exit-code 1 "$export_dir"
else
  docker run --rm --network none -v "$export_dir:/src:ro" \
    --entrypoint gitleaks "${CODESENTRY_GITLEAKS_IMAGE:-ghcr.io/gitleaks/gitleaks:v8.26.0}" \
    dir --config /src/.gitleaks.toml --redact --exit-code 1 /src
fi
