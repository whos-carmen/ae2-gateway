# AE2 Gateway

AE2 Gateway is a server-side Forge mod and private web dashboard for observing
an Applied Energistics 2 network and submitting deliberately confirmed crafting
requests.

The project has three deployable layers:

- `mod/` — Forge 1.20.1 mod that owns AE2 access, grid ACLs and crafting safety.
- `sidecar/` — loopback reverse proxy that validates Cloudflare Access identity.
- `dashboard/` — dense React interface embedded into the sidecar release.

The browser never connects to the Minecraft mod directly. In production,
Cloudflare Tunnel publishes only the sidecar. The mod API remains on loopback
and requires a separate service credential.

```text
Browser
  → Cloudflare Access
  → Cloudflare Tunnel
  → Go sidecar (loopback)
  → Forge mod API (loopback)
  → AE2 grid
```

## Development

Prerequisites:

- Temurin/OpenJDK 17
- Go
- Node.js and pnpm

Copy `.env.example` to `.env` only for local development. Do not commit
credentials.

```sh
pnpm --dir dashboard install
pnpm --dir dashboard test
(cd sidecar && go test ./...)
(cd mod && ./gradlew test build)
```

The local Minecraft integration instance lives outside this repository at
`/Users/pixie/star-tech-2-test`.

```sh
./scripts/test-server.sh start      # Minecraft on 127.0.0.1:25566
./scripts/test-sidecar.sh start     # dashboard on http://127.0.0.1:8080
```

Local sidecar uses `AE2_DEV_IDENTITY` so Cloudflare Access is not required on
loopback.

## Cloudflare setup

No Cloudflare resources are created automatically. The host that runs Minecraft
should receive only a Tunnel connector credential — never an account-wide API
token, R2 key, or DNS token.

### 1. Choose a hostname

Pick a hostname on a zone already in Cloudflare, for example
`ae2-test.example.com`.

### 2. Authenticate `cloudflared`

On a trusted admin machine (not necessarily the Minecraft host):

```sh
brew install cloudflared   # or the Linux package
cloudflared tunnel login
```

If the browser downloads `cert.pem` instead of writing
`~/.cloudflared/cert.pem`, move it into place:

```sh
mkdir -p ~/.cloudflared
install -m 600 ~/Downloads/cert.pem ~/.cloudflared/cert.pem
```

That certificate is account/zone scoped. Keep it private.

### 3. Create the tunnel and DNS route

From this repo (after packaging Linux artifacts and installing the sidecar on
the host):

```sh
./scripts/setup-cloudflare-remote-test.sh ae2-test.example.com
```

Or manually:

```sh
cloudflared tunnel create ae2-gateway
cloudflared tunnel route dns ae2-gateway ae2-test.example.com
```

Install `/etc/cloudflared/config.yml` on the Minecraft host with ingress that
points only at the sidecar:

```yaml
tunnel: <TUNNEL_UUID>
credentials-file: /etc/cloudflared/<TUNNEL_UUID>.json

originRequest:
  connectTimeout: 10s
  httpHostHeader: ae2-test.example.com

ingress:
  - hostname: ae2-test.example.com
    service: http://127.0.0.1:8080
  - service: http_status:404
```

Validate and run:

```sh
cloudflared tunnel --config /etc/cloudflared/config.yml ingress validate
cloudflared tunnel --config /etc/cloudflared/config.yml run
```

Never publish the Minecraft port or the mod API (`8765`) through the tunnel.

### 4. Add a real Access login method

Zero Trust defaults to **One-time PIN** (email codes). Those emails are often
unreliable. Add Google, GitHub, or another IdP first:

1. Cloudflare One / Zero Trust → **Settings → Authentication**
   (or **Integrations → Identity providers**)
2. **Login methods → Add** → Google or GitHub
3. Create the OAuth app and use the callback Cloudflare shows, usually
   `https://<team>.cloudflareaccess.com/cdn-cgi/access/callback`
4. Only after that IdP exists will it appear under the Access application's
   **Login methods** list (application details cannot invent new IdPs)

Cloudflare's own account login can also be added as an IdP if you prefer.

### 5. Create the Access application

1. Zero Trust → **Access → Applications → Add** → self-hosted
2. Application domain: the same hostname as the tunnel (`ae2-test.example.com`)
3. Policy: Allow only your identity (email / IdP account). Prefer IdP MFA.
4. Copy the application **AUD** (audience) from the application details

Unauthenticated requests must redirect to
`https://<team>.cloudflareaccess.com/...`. Direct origin access must remain
impossible from the public internet.

### 6. Configure the sidecar for Access

On the host, set production env (no `AE2_DEV_IDENTITY`):

```sh
AE2_LISTEN_ADDR=127.0.0.1:8080
AE2_DIST_DIR=/opt/ae2-gateway/dashboard
AE2_PUBLIC_ORIGIN=https://ae2-test.example.com
AE2_MOD_URL=http://127.0.0.1:8765
AE2_MOD_SECRET=<same secret as config/ae2-gateway.json>
AE2_CF_ISSUER=https://<team>.cloudflareaccess.com
AE2_CF_AUDIENCE=<Access application AUD>
```

`AE2_PUBLIC_ORIGIN` must be exact (scheme + host). The sidecar rejects mismatched
`Host` / `Origin` headers. `AE2_DEV_IDENTITY` is loopback-only and must not be
set when `AE2_PUBLIC_ORIGIN` is a public hostname.

Templates live in `deploy/cloudflare/` and `deploy/systemd/`.

### 7. First login checklist

1. Open `https://ae2-test.example.com` in a private window → Access login
2. After login, the dashboard loads
3. Join the Minecraft server and link: dashboard **Generate link code**, then
   `/ae2gateway link <code>`
4. Look at an AE2 node and run `/ae2gateway enroll <alias>`

If the Minecraft test instance binds `127.0.0.1` only, reach it with an SSH
local forward, for example `ssh -L 25566:127.0.0.1:25566 <host>`.

## Deployment boundary

Nothing in this repository automatically copies files to, restarts, or changes
the production server. Production transfer is intentionally a manual,
separately approved operation. See `docs/deployment.md`.
