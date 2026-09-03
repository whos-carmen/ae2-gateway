#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="${TEST_DIR:-/Users/pixie/star-tech-2-test}"
JAR="$ROOT/mod/build/libs/ae2-gateway-0.1.0.jar"

if tmux has-session -t "${TEST_SESSION:-ae2-gateway-test}" 2>/dev/null; then
  echo "Refusing to replace the test mod while the test server is running." >&2
  exit 1
fi
if [[ ! -f "$JAR" ]]; then
  echo "Build the mod first: make mod" >&2
  exit 1
fi

mkdir -p "$TEST_DIR/mods/.disabled"
for old in "$TEST_DIR"/mods/ae2-gateway-*.jar; do
  [[ -e "$old" ]] || continue
  rm -f "$old"
done
install -m 600 "$JAR" "$TEST_DIR/mods/"
echo "Installed $(basename "$JAR") into the local test instance."
