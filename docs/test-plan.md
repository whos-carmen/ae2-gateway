# Integration and security test plan

## Baseline

- Boot the copied modpack on `127.0.0.1:25566` with the legacy web integration
  disabled.
- Confirm the restored world loads without data-fixer or missing-registry
  failures.
- Record startup duration and steady-state memory before adding the gateway.

## Grid lifecycle

- Enroll the intended grid while targeting an active AE2 node.
- Restart and confirm the gateway UUID and alias remain stable.
- Confirm an un-enrolled grid is never returned.
- Split and rejoin the enrolled network; verify the anchor either re-resolves
  safely or reports the grid offline without attaching to another grid.

## Authorization

- Reject requests without the mod service signature.
- Reject stale, malformed and replayed identity assertions.
- Reject an unlinked identity and a linked identity without the grid ACL.
- Verify read permission cannot submit a plan.
- Verify craft permission is scoped to one enrolled grid.
- Ensure loopback does not bypass authentication.

## Inventory and telemetry

- Compare item/fluid totals with an in-game terminal.
- Exercise filtering, sorting and pagination on the largest network.
- Confirm snapshots are immutable off-thread and stale state is visible.
- Record response sizes and server tick cost at each candidate polling interval.

## Crafting

- Reject arbitrary, expired and cross-grid item tokens.
- Reject zero, negative and over-limit amounts.
- Calculate a valid plan and display missing inputs without submitting it.
- Submit one inexpensive controlled recipe.
- Repeat the same idempotency key and confirm no duplicate craft is created.
- Revoke craft permission between plan and submission and confirm fail-closed
  behavior.
- Confirm there is no HTTP cancellation route.

## Sidecar and browser

- Refuse development identity mode on non-loopback listeners.
- Reject wrong Origin, Host, audience, issuer and expired Access assertions.
- Confirm incoming identity headers are stripped and regenerated.
- Confirm CSP and other secure headers are present.
- Verify dashboard keyboard navigation, loading, stale and offline states.
- Verify a private browser is redirected to Cloudflare Access after publication.
