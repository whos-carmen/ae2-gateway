#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ARTIFACTS="$ROOT/artifacts"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf "$ARTIFACTS"
install -d -m 700 "$ARTIFACTS"

(
  cd "$ROOT/dashboard"
  pnpm install --frozen-lockfile
  pnpm verify
)

(
  cd "$ROOT/sidecar"
  go fmt ./...
  go vet ./...
  go test ./...
  install -d build
  CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -trimpath -ldflags="-s -w" -o "$ARTIFACTS/ae2-dashboard-linux-amd64" .
)

(
  cd "$ROOT/mod"
  ./gradlew clean test build
)

install -m 600 "$ROOT/mod/build/libs/ae2-gateway-0.1.0.jar" "$ARTIFACTS/"
tar -czf "$ARTIFACTS/ae2-dashboard-assets.tar.gz" -C "$ROOT/dashboard/dist" .
install -m 600 "$ROOT/deploy/systemd/ae2-dashboard.service" "$ARTIFACTS/"
install -m 600 "$ROOT/deploy/cloudflare/config.yml.example" "$ARTIFACTS/cloudflared-config.yml.example"

(
  cd "$ARTIFACTS"
  shasum -a 256 \
    ae2-dashboard-linux-amd64 \
    ae2-gateway-0.1.0.jar \
    ae2-dashboard-assets.tar.gz \
    ae2-dashboard.service \
    cloudflared-config.yml.example > SHA256SUMS
)

echo "Artifacts prepared in $ARTIFACTS"
