#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
mkdir -p .codesentry/tls
if [[ -f .codesentry/tls/server.key || -f .codesentry/tls/server.crt ]]; then
  echo '证书文件已存在；请先检查，不会覆盖现有私钥。'
  exit 0
fi
openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 365 \
  -keyout .codesentry/tls/server.key -out .codesentry/tls/server.crt \
  -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1,IP:::1' \
  >/dev/null 2>&1
chmod 600 .codesentry/tls/server.key
echo '已创建本机 HTTPS 证书；请在本机信任 server.crt 后使用 https://127.0.0.1:5173。不会自动修改系统信任。'
