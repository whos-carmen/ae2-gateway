#!/usr/bin/env bash
set -euo pipefail

REMOTE_HOST="${REMOTE_HOST:-star-tech-2}"
REMOTE_DIR="${REMOTE_DIR:-/opt/star-tech-2}"
TEST_DIR="${TEST_DIR:-/Users/pixie/star-tech-2-test}"

if tmux has-session -t ae2-gateway-test 2>/dev/null; then
  echo "Refusing to synchronize while the test server is running." >&2
  exit 1
fi

install -d -m 700 "$TEST_DIR"

ssh -o BatchMode=yes "$REMOTE_HOST" \
  "cd \"$REMOTE_DIR\" && tar --exclude='./world' --exclude='./backups' --exclude='./logs' --exclude='*.pid' --exclude='*.lock' -czf - ." \
  | tar -xzf - -C "$TEST_DIR"

latest_backup="$(
  ssh -o BatchMode=yes "$REMOTE_HOST" \
    "python3 -c 'from pathlib import Path; print(max(Path(\"$REMOTE_DIR/backups\").glob(\"*.zip\"), key=lambda p: p.stat().st_mtime))'"
)"

backup_name="$(basename "$latest_backup")"
scp -q "$REMOTE_HOST:$latest_backup" "$TEST_DIR/$backup_name"

rm -rf "$TEST_DIR/world"
unzip -q "$TEST_DIR/$backup_name" -d "$TEST_DIR"
chmod -R u+rwX,go-rwx "$TEST_DIR"

python3 "$(dirname "$0")/configure-test-server.py" "$TEST_DIR"
echo "Test server synchronized from $backup_name."
