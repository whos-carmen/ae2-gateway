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

## Deployment boundary

Nothing in this repository automatically copies files to, restarts, or changes
the production server. Production transfer is intentionally a manual,
separately approved operation.
