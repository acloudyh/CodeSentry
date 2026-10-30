#!/bin/sh
set -eu

GITLEAKS_VERSION="8.26.0"
SPOTBUGS_VERSION="4.10.5"
SEMGREP_VERSION="1.119.0"

architecture="$(dpkg --print-architecture)"
case "$architecture" in
  arm64) gitleaks_arch="arm64" ;;
  amd64) gitleaks_arch="x64" ;;
  *) echo "Unsupported architecture: $architecture" >&2; exit 1 ;;
esac

python3 -m pip install --no-cache-dir --timeout 300 --retries 10 \
  "semgrep==${SEMGREP_VERSION}" \
  "opentelemetry-instrumentation-requests==0.46b0" \
  "opentelemetry-instrumentation==0.46b0" \
  "opentelemetry-util-http==0.46b0" \
  "opentelemetry-semantic-conventions==0.46b0"

temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
curl -fsSL --retry 4 \
  "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_linux_${gitleaks_arch}.tar.gz" \
  -o "$temporary/gitleaks.tar.gz"
tar -xzf "$temporary/gitleaks.tar.gz" -C "$temporary" gitleaks
install -m 0755 "$temporary/gitleaks" /usr/local/bin/gitleaks

curl -fsSL https://aquasecurity.github.io/trivy-repo/deb/public.key \
  | gpg --dearmor -o /usr/share/keyrings/trivy.gpg
echo "deb [signed-by=/usr/share/keyrings/trivy.gpg] https://aquasecurity.github.io/trivy-repo/deb generic main" \
  > /etc/apt/sources.list.d/trivy.list
apt-get update
apt-get install -y --no-install-recommends trivy

curl -fsSL --retry 4 \
  "https://github.com/spotbugs/spotbugs/releases/download/${SPOTBUGS_VERSION}/spotbugs-${SPOTBUGS_VERSION}.tgz" \
  -o "$temporary/spotbugs.tgz"
mkdir -p /opt/spotbugs
tar -xzf "$temporary/spotbugs.tgz" -C /opt/spotbugs --strip-components=1
ln -s /opt/spotbugs/bin/spotbugs /usr/local/bin/spotbugs

mkdir -p /opt/codesentry/tools
curl -fsSL --retry 4 \
  "https://repo1.maven.org/maven2/com/h3xstream/findsecbugs/findsecbugs-plugin/1.14.0/findsecbugs-plugin-1.14.0.jar" \
  -o /opt/codesentry/tools/findsecbugs-plugin-1.14.0.jar
curl -fsSL --retry 4 \
  "https://repo1.maven.org/maven2/com/alibaba/p3c/p3c-pmd/2.1.1/p3c-pmd-2.1.1-jar-with-dependencies.jar" \
  -o /opt/codesentry/tools/p3c-pmd-2.1.1-jar-with-dependencies.jar

semgrep --version
gitleaks version
trivy --version
spotbugs -version
test -s /opt/codesentry/tools/findsecbugs-plugin-1.14.0.jar
test -s /opt/codesentry/tools/p3c-pmd-2.1.1-jar-with-dependencies.jar

rm -rf /var/lib/apt/lists/* /root/.cache
