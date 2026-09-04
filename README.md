# AE2 Gateway

AE2 Gateway lets you inspect an Applied Energistics 2 (AE2) network and submit
confirmed crafting jobs from a private web dashboard.

The repository has three parts:

- `mod/`: Forge 1.20.1 mod that talks to AE2, enforces grid ACLs, and serves a
  loopback HTTP API
- `sidecar/`: Go process that authenticates Cloudflare Access and proxies
  allowlisted API routes to the mod
- `dashboard/`: React UI that the sidecar embeds in its release package

Browsers reach only the sidecar. The mod API stays on loopback and requires a
separate service secret.

```text
Browser
  → Cloudflare Access
  → Cloudflare Tunnel
  → Go sidecar (loopback)
  → Forge mod API (loopback)
  → AE2 grid
```

## Develop locally

You need:

- Temurin or OpenJDK 17
- Go
- Node.js and pnpm

Install dependencies and run tests:

```sh
pnpm --dir dashboard install
pnpm --dir dashboard test
(cd sidecar && go test ./...)
(cd mod && ./gradlew test build)
```

For local Minecraft testing, use a server copy outside this repo (for example
`/Users/pixie/star-tech-2-test`):

```sh
./scripts/test-server.sh start      # Minecraft on 127.0.0.1:25566
./scripts/test-sidecar.sh start     # dashboard on http://127.0.0.1:8080
```

For UI work with hot reload, also run:

```sh
pnpm --dir dashboard dev --host 127.0.0.1 --port 5173
```

Then open `http://127.0.0.1:5173`. The Vite server proxies `/api` to the
sidecar.

Local sidecar mode sets `AE2_DEV_IDENTITY`, so Cloudflare Access is not
required on loopback. Do not set `AE2_DEV_IDENTITY` in production.

Copy `.env.example` to `.env` only for local notes. Do not commit secrets.

## Set up Cloudflare

This repo does not create Cloudflare resources for you. Put only a Tunnel
connector credential on the Minecraft host. Do not copy account API tokens, R2
keys, or DNS tokens there.

### Choose a hostname

Use a hostname on a Cloudflare zone you control, such as
`ae2-test.example.com`.

### Authenticate cloudflared

On an admin machine:

```sh
brew install cloudflared   # or the Linux package
cloudflared tunnel login
```

If the browser downloads `cert.pem` instead of writing
`~/.cloudflared/cert.pem`, install it:

```sh
mkdir -p ~/.cloudflared
install -m 600 ~/Downloads/cert.pem ~/.cloudflared/cert.pem
```

Keep that certificate private.

### Create the tunnel and DNS record

After you package Linux artifacts and install the sidecar on the host, you can
run:

```sh
./scripts/setup-cloudflare-remote-test.sh ae2-test.example.com
```

Or create the tunnel manually:

```sh
cloudflared tunnel create ae2-gateway
cloudflared tunnel route dns ae2-gateway ae2-test.example.com
```

On the Minecraft host, install `/etc/cloudflared/config.yml` so ingress points
only at the sidecar:

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

Do not publish the Minecraft port or the mod API port (`8765`) through the
tunnel. Keep the catch-all `404` ingress rule.

### Add an Access login method

Zero Trust defaults to **One-time PIN**. Email delivery is often unreliable, so
add Google, GitHub, Cloudflare, or another identity provider first:

1. In Cloudflare One, open **Settings > Authentication** (or **Integrations >
   Identity providers**).
2. Click **Login methods > Add**, then choose Google or GitHub.
3. Create the OAuth app and use the callback Cloudflare shows, usually
   `https://<team>.cloudflareaccess.com/cdn-cgi/access/callback`.
4. After the IdP exists, it appears under the Access application **Login
   methods** list.

### Create the Access application

1. Open **Access > Applications > Add**, then choose self-hosted.
2. Set the application domain to the tunnel hostname
   (`ae2-test.example.com`).
3. Allow only the identities you intend to use. Prefer IdP MFA.
4. Copy the application audience (`AUD`) from the application details.

Unauthenticated browser requests must redirect to
`https://<team>.cloudflareaccess.com/...`. The origin must stay unreachable
from the public internet.

### Configure the sidecar

On the host, set production environment variables and omit
`AE2_DEV_IDENTITY`:

```sh
AE2_LISTEN_ADDR=127.0.0.1:8080
AE2_DIST_DIR=/opt/ae2-gateway/dashboard
AE2_PUBLIC_ORIGIN=https://ae2-test.example.com
AE2_MOD_URL=http://127.0.0.1:8765
AE2_MOD_SECRET=<same secret as config/ae2-gateway.json>
AE2_CF_ISSUER=https://<team>.cloudflareaccess.com
AE2_CF_AUDIENCE=<Access application AUD>
```

`AE2_PUBLIC_ORIGIN` must match the browser origin exactly. The sidecar rejects
mismatched `Host` and `Origin` headers.

Templates are in `deploy/cloudflare/` and `deploy/systemd/`.

### Complete first login

1. Open `https://ae2-test.example.com` in a private window and sign in through
   Access.
2. In the dashboard, click **Generate link code**.
3. Join the Minecraft server and run `/ae2gateway link <code>`.
4. Look at an AE2 node and run `/ae2gateway enroll <alias>`.

If the Minecraft instance binds only `127.0.0.1`, forward a local port:

```sh
ssh -L 25566:127.0.0.1:25566 <host>
```

Then connect Minecraft to `127.0.0.1:25566`.

## Deploy to production

This repository does not copy files to, restart, or change the production
server. Treat production transfer as a manual, separately approved step. See
[Deployment](docs/deployment.md).
