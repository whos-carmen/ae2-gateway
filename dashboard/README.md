# Dashboard

React UI for AE2 Gateway. The sidecar serves the production build from
`dist/`.

## Develop

```sh
pnpm install
pnpm dev --host 127.0.0.1 --port 5173
```

Vite proxies `/api` to `http://127.0.0.1:8080` by default. Start the local
sidecar first so API calls succeed.

## Test and build

```sh
pnpm test
pnpm build
```

`pnpm build` writes assets to `dist/`. The sidecar and packaging scripts consume
that directory.
