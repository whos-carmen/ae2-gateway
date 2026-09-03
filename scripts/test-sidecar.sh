#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="${TEST_DIR:-/Users/pixie/star-tech-2-test}"
SESSION="${SIDECAR_TEST_SESSION:-ae2-gateway-sidecar-test}"
LISTEN="${AE2_LISTEN_ADDR:-127.0.0.1:8080}"
ORIGIN="${AE2_PUBLIC_ORIGIN:-http://127.0.0.1:8080}"
DEV_IDENTITY="${AE2_DEV_IDENTITY:-dev@localhost.invalid}"

case "${1:-status}" in
  start)
    if tmux has-session -t "$SESSION" 2>/dev/null; then
      echo "Test sidecar is already running."
      exit 0
    fi
    config="$TEST_DIR/config/ae2-gateway.json"
    [[ -f "$config" ]] || {
      echo "Start the test server once so the mod creates $config." >&2
      exit 1
    }
    secret="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["serviceSecret"])' "$config")"
    [[ ${#secret} -ge 32 ]] || {
      echo "The mod service secret is missing or too short." >&2
      exit 1
    }
    (
      cd "$ROOT/dashboard"
      pnpm build
    )
    (
      cd "$ROOT/sidecar"
      mkdir -p build
      go build -o build/ae2-dashboard .
    )
    tmux new-session -d -s "$SESSION" -c "$ROOT/sidecar" \
      "exec env AE2_LISTEN_ADDR='$LISTEN' AE2_DIST_DIR='$ROOT/dashboard/dist' AE2_PUBLIC_ORIGIN='$ORIGIN' AE2_MOD_URL='http://127.0.0.1:8765' AE2_MOD_SECRET='$secret' AE2_DEV_IDENTITY='$DEV_IDENTITY' AE2_MUTATION_RATE_PER_MINUTE='240' AE2_MUTATION_RATE_BURST='60' AE2_RATE_PER_MINUTE='600' AE2_RATE_BURST='120' ./build/ae2-dashboard"
    echo "Started test sidecar at $ORIGIN."
    ;;
  stop)
    if tmux has-session -t "$SESSION" 2>/dev/null; then
      tmux kill-session -t "$SESSION"
    fi
    echo "Test sidecar stopped."
    ;;
  status)
    if tmux has-session -t "$SESSION" 2>/dev/null; then
      echo "running"
    else
      echo "stopped"
    fi
    ;;
  logs)
    exec tmux capture-pane -p -S -200 -t "$SESSION"
    ;;
  *)
    echo "usage: $0 {start|stop|status|logs}" >&2
    exit 2
    ;;
esac
