#!/usr/bin/env bash
set -euo pipefail

SERVER_DIR="${TEST_DIR:-/Users/pixie/star-tech-2-test}"
SESSION="${TEST_SESSION:-ae2-gateway-test}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
JAVA_BIN="$JAVA_HOME/bin/java"
FORGE_ARGS="libraries/net/minecraftforge/forge/1.20.1-47.4.20/unix_args.txt"

case "${1:-status}" in
  start)
    if tmux has-session -t "$SESSION" 2>/dev/null; then
      echo "Test server is already running."
      exit 0
    fi
    test -x "$JAVA_BIN"
    test -f "$SERVER_DIR/$FORGE_ARGS"
    tmux new-session -d -s "$SESSION" -c "$SERVER_DIR" \
      "exec env JAVA_HOME='$JAVA_HOME' PATH='$JAVA_HOME/bin:/usr/bin:/bin' '$JAVA_BIN' @user_jvm_args.txt '@$FORGE_ARGS' nogui"
    echo "Started test server in tmux session $SESSION."
    ;;
  stop)
    if ! tmux has-session -t "$SESSION" 2>/dev/null; then
      echo "Test server is not running."
      exit 0
    fi
    tmux send-keys -t "$SESSION" "stop" Enter
    echo "Requested a clean test-server shutdown."
    ;;
  status)
    if tmux has-session -t "$SESSION" 2>/dev/null; then
      echo "running"
    else
      echo "stopped"
    fi
    ;;
  console)
    exec tmux attach-session -t "$SESSION"
    ;;
  logs)
    if [[ -f "$SERVER_DIR/logs/latest.log" ]]; then
      exec tail -n 120 "$SERVER_DIR/logs/latest.log"
    fi
    echo "No test-server log exists yet." >&2
    exit 1
    ;;
  *)
    echo "usage: $0 {start|stop|status|console|logs}" >&2
    exit 2
    ;;
esac
