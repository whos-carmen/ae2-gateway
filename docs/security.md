# Security model

## Trust boundaries

- Cloudflare Access authenticates a browser identity but does not grant access
  to any ME network.
- The sidecar verifies the Access token and forwards a short-lived signed
  assertion. Incoming identity headers are never trusted.
- The mod verifies the sidecar signature, resolves the identity-to-player
  binding, and enforces read or craft permission for every grid operation.
- AE2 and Minecraft state are accessed only from the server thread.

The sidecar and mod listeners are loopback-only. A loopback source address does
not bypass authentication.

## Crafting invariants

- Clients select an opaque key issued by the current inventory snapshot; they
  cannot submit arbitrary item components or NBT.
- Amounts, request bodies, active calculations and queue depth are bounded.
- Planning and submission independently recheck identity, grid ACL and grid
  anchor.
- Plans expire and can be submitted once.
- An idempotency key returns the first terminal submission result.
- The API does not expose crafting cancellation.

## Production requirements

- Cloudflare Access must be configured before the Tunnel connector starts.
- Development identity mode must be disabled.
- Mod and sidecar secrets must be independent random values and readable only
  by their service accounts.
- Only the sidecar hostname is routed by the tunnel; the Minecraft and mod API
  ports are never tunnel ingress services.
- Audit logs must avoid tokens, item components and request bodies.

## Explicitly out of scope

- Protection from a compromised Minecraft host or operator account.
- Availability during host, ISP, Cloudflare or identity-provider outages.
- Exact attribution inside AE2 versions that only support a service/fake-player
  action source. The gateway audit log remains authoritative in that case.
