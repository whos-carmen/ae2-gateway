# Security

## Trust boundaries

- Cloudflare Access authenticates the browser identity. It does not grant ME
  network access by itself.
- The sidecar verifies the Access JWT and issues a short-lived signed
  assertion. It ignores inbound identity headers from the client.
- The mod verifies the sidecar signature, maps the identity to a Minecraft
  player link, and enforces read or craft permission for each grid call.
- The mod touches AE2 and Minecraft state only on the server thread.

The sidecar and mod listen on loopback only. A loopback source address does not
bypass authentication.

## Crafting rules

- Clients use opaque item keys from the current inventory snapshot. They cannot
  submit arbitrary item components or NBT.
- Amounts, request bodies, active calculations, and queue depth are bounded.
- Plan calculation and submission each recheck identity, grid ACL, and grid
  anchor.
- Plans expire and can be submitted once.
- An idempotency key returns the first terminal submission result.
- The API does not expose crafting cancellation.

## Production requirements

- Configure Cloudflare Access before you start the Tunnel connector.
- Disable development identity mode.
- Use independent random secrets for the mod and sidecar, readable only by
  their service accounts.
- Route only the sidecar hostname through the tunnel. Do not expose Minecraft
  or the mod API as tunnel ingress.
- Keep tokens, item components, and request bodies out of audit logs.

## Out of scope

- A compromised Minecraft host or operator account
- Availability during host, ISP, Cloudflare, or identity-provider outages
- Exact in-game attribution on AE2 builds that only expose a service or
  fake-player action source; use the gateway audit log in that case
