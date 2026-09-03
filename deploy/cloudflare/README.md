# Cloudflare publication checklist

No Cloudflare resources are created by this repository automatically.

## Required account preparation

1. Choose a hostname such as `ae2.example.com`.
2. Authenticate `cloudflared` interactively, or create a narrowly scoped token:
   - Account: Cloudflare Tunnel Edit
   - Account: Access Apps and Policies Edit
   - Zone: DNS Edit
   - Zone: Zone Read
3. Create a named tunnel and route only the chosen hostname.
4. Create the Access self-hosted application before starting the connector.
5. Use an Allow policy containing only the intended identities or IdP group and
   require MFA at the identity provider.
6. Copy the application audience (`AUD`) into the sidecar environment.
7. Test unauthenticated access from a private browser window. It must redirect
   to Access and the origin must remain unreachable directly.

The production host receives only the tunnel connector credential. Do not copy
an account-management API token, R2 key, or DNS token to the Minecraft server.

## Connector

Copy `config.yml.example` to the host's protected cloudflared configuration,
replace the tunnel UUID, credential path and hostname, and validate:

```sh
cloudflared tunnel ingress validate
cloudflared tunnel ingress rule https://ae2.example.com
```

The final catch-all 404 ingress rule is mandatory.
