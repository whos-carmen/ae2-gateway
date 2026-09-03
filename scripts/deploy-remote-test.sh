#!/usr/bin/env bash
# Create/update an isolated AE2 Gateway test instance on star-tech-2.
# Does not touch the production Minecraft process or /opt/star-tech-2 runtime.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE_HOST="${REMOTE_HOST:-star-tech-2}"
PROD_DIR="${PROD_DIR:-/opt/star-tech-2}"
TEST_DIR="${REMOTE_TEST_DIR:-/opt/star-tech-2-test}"
GATEWAY_DIR="${REMOTE_GATEWAY_DIR:-/opt/ae2-gateway-test}"
SESSION="${REMOTE_TEST_SESSION:-ae2-gateway-test}"
SIDECAR_SESSION="${REMOTE_SIDECAR_SESSION:-ae2-gateway-sidecar-test}"
ARTIFACTS="$ROOT/artifacts"

need() {
  [[ -f "$1" ]] || {
    echo "Missing $1 — run ./scripts/package.sh first." >&2
    exit 1
  }
}

need "$ARTIFACTS/ae2-gateway-0.1.0.jar"
need "$ARTIFACTS/ae2-dashboard-linux-amd64"
need "$ARTIFACTS/ae2-dashboard-assets.tar.gz"

ssh -o BatchMode=yes "$REMOTE_HOST" "tmux has-session -t '$SESSION' 2>/dev/null" && {
  echo "Refusing to refresh while remote test session '$SESSION' is running." >&2
  echo "Stop it first: ssh $REMOTE_HOST \"tmux kill-session -t $SESSION\"" >&2
  exit 1
}
ssh -o BatchMode=yes "$REMOTE_HOST" "tmux has-session -t '$SIDECAR_SESSION' 2>/dev/null" && {
  echo "Stopping remote test sidecar session '$SIDECAR_SESSION'."
  ssh -o BatchMode=yes "$REMOTE_HOST" "tmux kill-session -t '$SIDECAR_SESSION'"
}

echo "Syncing production tree into $TEST_DIR on $REMOTE_HOST (world from latest backup)..."
ssh -o BatchMode=yes "$REMOTE_HOST" bash -s -- "$PROD_DIR" "$TEST_DIR" <<'REMOTE'
set -euo pipefail
PROD_DIR="$1"
TEST_DIR="$2"
rm -rf "$TEST_DIR"
install -d -m 700 "$TEST_DIR"
(
  cd "$PROD_DIR"
  tar --exclude='./world' --exclude='./backups' --exclude='./logs' \
      --exclude='*.pid' --exclude='*.lock' -cf - .
) | tar -xf - -C "$TEST_DIR"
latest="$(python3 -c "from pathlib import Path; print(max(Path('$PROD_DIR/backups').glob('*.zip'), key=lambda p: p.stat().st_mtime))")"
rm -rf "$TEST_DIR/world"
unzip -q "$latest" -d "$TEST_DIR"
chmod -R u+rwX,go-rwx "$TEST_DIR"
echo "Restored world from $(basename "$latest")"
REMOTE

echo "Configuring isolated ports/heap and disabling ae2webintegration..."
scp -q "$ROOT/scripts/configure-test-server.py" "$REMOTE_HOST:/tmp/configure-ae2-gateway-test.py"
ssh -o BatchMode=yes "$REMOTE_HOST" "python3 /tmp/configure-ae2-gateway-test.py '$TEST_DIR' && rm -f /tmp/configure-ae2-gateway-test.py"

# Remote host has less free RAM than the Mac; keep the test heap modest beside production.
ssh -o BatchMode=yes "$REMOTE_HOST" bash -s -- "$TEST_DIR" <<'REMOTE'
set -euo pipefail
TEST_DIR="$1"
python3 - <<PY
from pathlib import Path
import re
path = Path("$TEST_DIR") / "user_jvm_args.txt"
text = path.read_text()
text = re.sub(r"(?m)^\s*-Xm[xs]\S+\s*$", "", text)
path.write_text(text.rstrip() + "\n-Xms2G\n-Xmx6G\n")
props = Path("$TEST_DIR") / "server.properties"
lines = []
for line in props.read_text().splitlines():
    if line.startswith("motd="):
        lines.append("motd=AE2 Gateway Remote Test")
    else:
        lines.append(line)
props.write_text("\n".join(lines) + "\n")
PY
REMOTE

echo "Installing gateway mod into remote test mods..."
ssh -o BatchMode=yes "$REMOTE_HOST" "mkdir -p '$TEST_DIR/mods/.disabled' && rm -f '$TEST_DIR'/mods/ae2-gateway-*.jar"
scp -q "$ARTIFACTS/ae2-gateway-0.1.0.jar" "$REMOTE_HOST:$TEST_DIR/mods/ae2-gateway-0.1.0.jar"
ssh -o BatchMode=yes "$REMOTE_HOST" "chmod 600 '$TEST_DIR/mods/ae2-gateway-0.1.0.jar'"

echo "Installing sidecar under $GATEWAY_DIR..."
ssh -o BatchMode=yes "$REMOTE_HOST" bash -s -- "$GATEWAY_DIR" <<'REMOTE'
set -euo pipefail
GATEWAY_DIR="$1"
install -d -m 755 "$GATEWAY_DIR/bin" "$GATEWAY_DIR/dashboard"
REMOTE
scp -q "$ARTIFACTS/ae2-dashboard-linux-amd64" "$REMOTE_HOST:$GATEWAY_DIR/bin/ae2-dashboard"
ssh -o BatchMode=yes "$REMOTE_HOST" "chmod 755 '$GATEWAY_DIR/bin/ae2-dashboard'"
ssh -o BatchMode=yes "$REMOTE_HOST" "rm -rf '$GATEWAY_DIR/dashboard'/*"
scp -q "$ARTIFACTS/ae2-dashboard-assets.tar.gz" "$REMOTE_HOST:/tmp/ae2-dashboard-assets.tar.gz"
ssh -o BatchMode=yes "$REMOTE_HOST" \
  "tar -xzf /tmp/ae2-dashboard-assets.tar.gz -C '$GATEWAY_DIR/dashboard' && rm -f /tmp/ae2-dashboard-assets.tar.gz && chmod -R go-rwx '$GATEWAY_DIR'"

echo "Remote test instance prepared at $TEST_DIR"
echo "Start MC:  ssh $REMOTE_HOST \"cd $TEST_DIR && tmux new -s $SESSION -d 'java @user_jvm_args.txt @libraries/net/minecraftforge/forge/1.20.1-47.4.20/unix_args.txt nogui'\""
echo "Then start sidecar after ae2-gateway.json exists."
