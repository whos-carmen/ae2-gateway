# Cloudflare checklist

This repo does not create Cloudflare resources automatically. For the full
setup, see [Cloudflare setup](../../README.md#set-up-cloudflare) in the root
README.

## Prepare the account

1. Choose a hostname such as `ae2.example.com`.
2. Authenticate `cloudflared` with `cloudflared tunnel login`, or create a
   narrowly scoped API token with:
   - Account: Cloudflare Tunnel Edit
   - Account: Access Apps and Policies Edit
   - Zone: DNS Edit
   - Zone: Zone Read
3. Create a named tunnel and route only that hostname.
4. Add an Access identity provider such as Google, GitHub, or Cloudflare
   before you rely on One-time PIN email codes.
5. Create the Access self-hosted application before you start the connector.
6. Allow only the identities or IdP groups you intend to use. Prefer IdP MFA.
7. Copy the application audience (`AUD`) into the sidecar environment.
8. From a private browser window, confirm unauthenticated access redirects to
   Access and that the origin is unreachable directly.

Put only the Tunnel connector credential on the Minecraft host. Do not copy an
account-management API token, R2 key, or DNS token there.

## Run the connector

Copy `config.yml.example` to the host cloudflared config, replace the tunnel
UUID, credential path, and hostname, then validate:

```sh
cloudflared tunnel --config /etc/cloudflared/config.yml ingress validate
cloudflared tunnel --config /etc/cloudflared/config.yml run
```

Keep the final catch-all `404` ingress rule.
