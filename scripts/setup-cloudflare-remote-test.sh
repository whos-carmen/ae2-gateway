#!/usr/bin/env bash
# After `cloudflared tunnel login` succeeds locally, create a named tunnel and
# install the connector config on star-tech-2 for the remote test sidecar.
#
# Usage:
#   ./scripts/setup-cloudflare-remote-test.sh ae2-test.example.com
set -euo pipefail

HOSTNAME="${1:-}"
if [[ -z "$HOSTNAME" ]]; then
  echo "usage: $0 <hostname>" >&2
  echo "example: $0 ae2-test.example.com" >&2
  exit 2
fi

REMOTE_HOST="${REMOTE_HOST:-star-tech-2}"
TUNNEL_NAME="${TUNNEL_NAME:-ae2-gateway-test}"
ORIGIN_CERT="${TUNNEL_ORIGIN_CERT:-$HOME/.cloudflared/cert.pem}"
LISTEN_ORIGIN="http://127.0.0.1:8080"

[[ -f "$ORIGIN_CERT" ]] || {
  echo "Missing $ORIGIN_CERT. Run: cloudflared tunnel login" >&2
  exit 1
}

export TUNNEL_ORIGIN_CERT="$ORIGIN_CERT"

existing_id="$(cloudflared tunnel list 2>/dev/null | awk -v n="$TUNNEL_NAME" '$2==n {print $1; exit}')"
if [[ -z "$existing_id" ]]; then
  echo "Creating tunnel $TUNNEL_NAME..."
  cloudflared tunnel create "$TUNNEL_NAME" >/tmp/ae2-tunnel-create.out
  cat /tmp/ae2-tunnel-create.out
fi

TUNNEL_ID="$(cloudflared tunnel list | awk -v n="$TUNNEL_NAME" '$2==n {print $1; exit}')"
[[ -n "$TUNNEL_ID" ]] || {
  echo "Could not resolve tunnel id for $TUNNEL_NAME" >&2
  exit 1
}

CRED_SRC="$HOME/.cloudflared/${TUNNEL_ID}.json"
[[ -f "$CRED_SRC" ]] || {
  echo "Missing tunnel credentials at $CRED_SRC" >&2
  exit 1
}

echo "Routing DNS $HOSTNAME -> tunnel $TUNNEL_ID..."
cloudflared tunnel route dns --overwrite-dns "$TUNNEL_NAME" "$HOSTNAME"

CFG_DIR="$(mktemp -d)"
cat > "$CFG_DIR/config.yml" <<EOF
tunnel: $TUNNEL_ID
credentials-file: /etc/cloudflared/${TUNNEL_ID}.json

originRequest:
  connectTimeout: 10s
  httpHostHeader: $HOSTNAME

ingress:
  - hostname: $HOSTNAME
    service: $LISTEN_ORIGIN
  - service: http_status:404
EOF

echo "Installing connector config on $REMOTE_HOST..."
ssh -o BatchMode=yes "$REMOTE_HOST" 'install -d -m 700 /etc/cloudflared'
scp -q "$CRED_SRC" "$REMOTE_HOST:/etc/cloudflared/${TUNNEL_ID}.json"
scp -q "$CFG_DIR/config.yml" "$REMOTE_HOST:/etc/cloudflared/config.yml"
ssh -o BatchMode=yes "$REMOTE_HOST" "chmod 600 /etc/cloudflared/${TUNNEL_ID}.json /etc/cloudflared/config.yml"

ssh -o BatchMode=yes "$REMOTE_HOST" 'cloudflared tunnel --config /etc/cloudflared/config.yml ingress validate'

# Prefer a dedicated tmux session for the test connector (does not use system service yet).
SESSION="${REMOTE_CF_SESSION:-ae2-gateway-cloudflared-test}"
ssh -o BatchMode=yes "$REMOTE_HOST" bash -s -- "$SESSION" <<'REMOTE'
set -euo pipefail
SESSION="$1"
if tmux has-session -t "$SESSION" 2>/dev/null; then
  tmux kill-session -t "$SESSION"
fi
tmux new-session -d -s "$SESSION" \
  'exec cloudflared tunnel --config /etc/cloudflared/config.yml run'
echo "cloudflared session $SESSION started"
REMOTE

rm -rf "$CFG_DIR"

echo
echo "Tunnel connector is running for https://$HOSTNAME"
echo "Next (Cloudflare Zero Trust dashboard):"
echo "  1. Create a self-hosted Access application for $HOSTNAME"
echo "  2. Allow only your identity / group; require MFA at the IdP"
echo "  3. Copy the application AUD into /etc/ae2-gateway-test/dashboard.env as AE2_CF_AUDIENCE"
echo "  4. Set AE2_CF_ISSUER to https://<team>.cloudflareaccess.com"
echo "  5. Set AE2_PUBLIC_ORIGIN=https://$HOSTNAME"
echo "  6. Clear AE2_DEV_IDENTITY and restart the sidecar"
echo
echo "Until Access is wired, keep DEV_IDENTITY for Tailscale/loopback tests only."
