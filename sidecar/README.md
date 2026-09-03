# AE2 gateway sidecar

The sidecar is the only browser-facing process. It serves the dashboard, authenticates Cloudflare Access users, applies request policy, and forwards only the routes declared in `../api/openapi.yaml` to the loopback mod API.

## Configuration

All configuration comes from environment variables. The process does not load `.env` files.

Required in every mode:

- `AE2_LISTEN_ADDR`: the single `host:port` passed to the HTTP server. The host must be a literal loopback IP in every mode because `cloudflared` and the sidecar run on the same machine.
- `AE2_DIST_DIR`: directory containing the dashboard `index.html` and assets.
- `AE2_PUBLIC_ORIGIN`: exact browser origin, including a non-default port when applicable. Its host is also the only accepted `Host`.
- `AE2_MOD_URL`: an `http` origin using a literal loopback IP.
- `AE2_MOD_SECRET`: at least 32 bytes of random service credential material.

Required in production:

- `AE2_CF_ISSUER`: exact Cloudflare Access team issuer, such as `https://team.cloudflareaccess.com`.
- `AE2_CF_AUDIENCE`: Access application audience (`aud`).

Optional limits are documented in `.env.example`.

Production authentication accepts the Access token from `Cf-Access-Jwt-Assertion` or the `CF_Authorization` cookie. If both are present they must match. Tokens must have an RS256 signature from the issuer's `/cdn-cgi/access/certs` JWKS, and valid issuer, audience, expiration, subject, and email claims. JWKS responses are size-bounded and cached for 15 minutes; unknown-key refreshes are throttled.

For local testing only, set `AE2_DEV_IDENTITY` to a plain email, bind `AE2_LISTEN_ADDR` to a literal loopback IP, and use a literal loopback host in `AE2_PUBLIC_ORIGIN`. Startup rejects wildcard, hostname, and non-loopback binds/origins in this mode. Each request must also originate from loopback. Never deploy with this variable set. Production requires an `https` public origin.

## Browser and mod protocol

Mutating requests require:

1. an exact `Origin` match;
2. an `ae2_csrf` `SameSite=Strict` cookie issued while serving the dashboard;
3. the same value in `X-CSRF-Token`;
4. `Content-Type: application/json` for the crafting-plan body; and
5. a 16–128 character `Idempotency-Key` for plan submission.

The proxy constructs outbound headers from an allowlist, so client `CF-*`, forwarding, authorization, cookie, and `X-AE2-*` headers are not forwarded. Every mod request receives:

- `X-AE2-Mod-Secret`: the shared service credential.
- `X-AE2-Identity-Assertion`: `base64url(payload).base64url(HMAC-SHA256(payload))`.
- `X-Request-ID`: a random request identifier.

The assertion payload contains `sub`, `email`, `iat`, `exp`, `jti`, `method`, and `path`. It expires after 30 seconds and is bound to the proxied request. The mod must compare HMAC values in constant time, reject expired/future assertions and reused `jti` values, and verify the method/path binding before authorizing the identity.

`/healthz` reports sidecar process health without requiring authentication. It does not test Minecraft or the mod. `/api/v1/health` remains an authenticated, allowlisted mod route.

## Build and test

```sh
go build -trimpath -ldflags="-s -w -X main.version=$(git describe --always --dirty 2>/dev/null || echo unknown)" .
go vet ./...
go test ./...
```
