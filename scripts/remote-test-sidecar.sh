#!/usr/bin/env bash
# Start/stop the remote test sidecar on star-tech-2 (loopback only).
set -euo pipefail

REMOTE_HOST="${REMOTE_HOST:-star-tech-2}"
TEST_DIR="${REMOTE_TEST_DIR:-/opt/star-tech-2-test}"
GATEWAY_DIR="${REMOTE_GATEWAY_DIR:-/opt/ae2-gateway-test}"
SESSION="${REMOTE_SIDECAR_SESSION:-ae2-gateway-sidecar-test}"
LISTEN="${AE2_LISTEN_ADDR:-127.0.0.1:8080}"
ORIGIN="${AE2_PUBLIC_ORIGIN:-http://127.0.0.1:8080}"
MOD_URL="${AE2_MOD_URL:-http://127.0.0.1:8765}"

case "${1:-status}" in
  start)
    ssh -o BatchMode=yes "$REMOTE_HOST" bash -s -- "$TEST_DIR" "$GATEWAY_DIR" "$SESSION" "$LISTEN" "$ORIGIN" "$MOD_URL" <<'REMOTE'
set -euo pipefail
TEST_DIR="$1"; GATEWAY_DIR="$2"; SESSION="$3"; LISTEN="$4"; ORIGIN="$5"; MOD_URL="$6"
if tmux has-session -t "$SESSION" 2>/dev/null; then
  echo "Remote test sidecar already running."
  exit 0
fi
config="$TEST_DIR/config/ae2-gateway.json"
[[ -f "$config" ]] || {
  echo "Missing $config — start the remote test Minecraft once first." >&2
  exit 1
}
secret="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["serviceSecret"])' "$config")"
[[ ${#secret} -ge 32 ]] || {
  echo "The mod service secret is missing or too short." >&2
  exit 1
}
install -d -m 700 /etc/ae2-gateway-test
umask 077
cat > /etc/ae2-gateway-test/dashboard.env <<EOF
AE2_LISTEN_ADDR=$LISTEN
AE2_DIST_DIR=$GATEWAY_DIR/dashboard
AE2_PUBLIC_ORIGIN=$ORIGIN
AE2_MOD_URL=$MOD_URL
AE2_MOD_SECRET=$secret
AE2_DEV_IDENTITY=dev@localhost.invalid
AE2_MUTATION_RATE_PER_MINUTE=240
AE2_MUTATION_RATE_BURST=60
AE2_RATE_PER_MINUTE=600
AE2_RATE_BURST=120
EOF
chmod 600 /etc/ae2-gateway-test/dashboard.env
# shellcheck disable=SC2086
set -a
# shellcheck disable=SC1091
source /etc/ae2-gateway-test/dashboard.env
set +a
tmux new-session -d -s "$SESSION" -c "$GATEWAY_DIR" \
  "exec env AE2_LISTEN_ADDR='$LISTEN' AE2_DIST_DIR='$GATEWAY_DIR/dashboard' AE2_PUBLIC_ORIGIN='$ORIGIN' AE2_MOD_URL='$MOD_URL' AE2_MOD_SECRET='$secret' AE2_DEV_IDENTITY='dev@localhost.invalid' AE2_MUTATION_RATE_PER_MINUTE='240' AE2_MUTATION_RATE_BURST='60' AE2_RATE_PER_MINUTE='600' AE2_RATE_BURST='120' '$GATEWAY_DIR/bin/ae2-dashboard'"
echo "Started remote test sidecar at $ORIGIN"
REMOTE
    ;;
  stop)
    ssh -o BatchMode=yes "$REMOTE_HOST" "tmux has-session -t '$SESSION' 2>/dev/null && tmux kill-session -t '$SESSION' || true"
    echo "Remote test sidecar stopped."
    ;;
  status)
    ssh -o BatchMode=yes "$REMOTE_HOST" "if tmux has-session -t '$SESSION' 2>/dev/null; then echo running; else echo stopped; fi"
    ;;
  logs)
    ssh -o BatchMode=yes "$REMOTE_HOST" "tmux capture-pane -p -S -200 -t '$SESSION'"
    ;;
  *)
    echo "usage: $0 {start|stop|status|logs}" >&2
    exit 2
    ;;
esac
