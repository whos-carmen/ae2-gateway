# Sidecar

The sidecar is the only browser-facing process. It serves the dashboard,
authenticates Cloudflare Access users, applies request policy, and forwards
only the routes in `../api/openapi.yaml` to the loopback mod API.

## Configure

Configuration comes from environment variables. The process does not load
`.env` files.

Required in every mode:

- `AE2_LISTEN_ADDR`: `host:port` for the HTTP server. Use a literal loopback IP
  in every mode because `cloudflared` and the sidecar run on the same machine.
- `AE2_DIST_DIR`: directory that contains the dashboard `index.html` and
  assets.
- `AE2_PUBLIC_ORIGIN`: exact browser origin, including a non-default port when
  needed. Its host is also the only accepted `Host`.
- `AE2_MOD_URL`: an `http` origin with a literal loopback IP.
- `AE2_MOD_SECRET`: at least 32 bytes of random service credential material.

Required in production:

- `AE2_CF_ISSUER`: Cloudflare Access team issuer, such as
  `https://team.cloudflareaccess.com`
- `AE2_CF_AUDIENCE`: Access application audience (`aud`)

Optional limits are listed in `.env.example`.

Production authentication accepts the Access token from
`Cf-Access-Jwt-Assertion` or the `CF_Authorization` cookie. If both are
present, they must match. Tokens must use an RS256 signature from the issuer
`/cdn-cgi/access/certs` JWKS, and they must carry valid issuer, audience,
expiration, subject, and email claims. JWKS responses are size-bounded and
cached for 15 minutes. Unknown-key refreshes are throttled.

For local testing only, set `AE2_DEV_IDENTITY` to a plain email, bind
`AE2_LISTEN_ADDR` to a literal loopback IP, and use a literal loopback host in
`AE2_PUBLIC_ORIGIN`. Startup rejects wildcard, hostname, and non-loopback binds
or origins in this mode. Each request must also come from loopback. Do not
deploy with `AE2_DEV_IDENTITY` set. Production requires an `https` public
origin.

### Tailscale-trusted mode

To run the dashboard directly on a tailnet address without Cloudflare, set:

- `AE2_TRUST_TAILSCALE=1`
- `AE2_TAILSCALE_IDENTITY`: the identity every request is treated as, a plain
  email address
- `AE2_LISTEN_ADDR`: a literal loopback or `100.64.0.0/10` (Tailscale) address
- `AE2_PUBLIC_ORIGIN`: the same address as a browser origin, `http` or `https`. A
  comma-separated list is accepted; the first entry is canonical and the rest
  are additional accepted hosts and origins.

Tailnet membership is then the access boundary. Bind only the Tailscale address
and never publish that port beyond the tailnet. `AE2_DEV_IDENTITY` and the
Cloudflare settings are ignored in this mode and must not be combined with it.

## Browser and mod protocol

Mutating requests require:

1. An exact `Origin` match
2. An `ae2_csrf` `SameSite=Strict` cookie issued while serving the dashboard
3. The same value in `X-CSRF-Token`
4. `Content-Type: application/json` for the crafting-plan body
5. A 16–128 character `Idempotency-Key` for plan submission

The proxy builds outbound headers from an allowlist, so client `CF-*`,
forwarding, authorization, cookie, and `X-AE2-*` headers are not forwarded.
Every mod request receives:

- `X-AE2-Mod-Secret`: shared service credential
- `X-AE2-Identity-Assertion`: `base64url(payload).base64url(HMAC-SHA256(payload))`
- `X-Request-ID`: random request identifier

The assertion payload contains `sub`, `email`, `iat`, `exp`, `jti`, `method`,
and `path`. It expires after 30 seconds and is bound to the proxied request.
The mod must compare HMAC values in constant time, reject expired or future
assertions and reused `jti` values, and verify the method and path binding
before it authorizes the identity.

`/healthz` reports sidecar process health without authentication. It does not
test Minecraft or the mod. `/api/v1/health` remains an authenticated,
allowlisted mod route.

## Build and test

```sh
go build -trimpath -ldflags="-s -w -X main.version=$(git describe --always --dirty 2>/dev/null || echo unknown)" .
go vet ./...
go test ./...
```
