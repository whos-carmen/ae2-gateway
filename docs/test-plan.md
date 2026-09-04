# Test plan

## Baseline

1. Boot the copied modpack on `127.0.0.1:25566` with the legacy web integration
   disabled.
2. Confirm the restored world loads without data-fixer or missing-registry
   failures.
3. Record startup duration and steady-state memory before you add the gateway.

## Grid lifecycle

1. Enroll the target grid while looking at an active AE2 node.
2. Restart and confirm the gateway UUID and alias stay stable.
3. Confirm an unenrolled grid never appears in the API.
4. Split and rejoin the enrolled network. Confirm the anchor either re-resolves
   safely or reports the grid offline without attaching to another grid.

## Authorization

1. Reject requests without the mod service signature.
2. Reject stale, malformed, and replayed identity assertions.
3. Reject an unlinked identity and a linked identity without the grid ACL.
4. Confirm read permission cannot submit a plan.
5. Confirm craft permission is scoped to one enrolled grid.
6. Confirm loopback does not bypass authentication.

## Inventory and telemetry

1. Compare item and fluid totals with an in-game terminal.
2. Exercise filtering, sorting, and pagination on the largest network.
3. Confirm snapshots stay immutable off-thread and stale state is visible.
4. Record response sizes and server tick cost for each polling interval you
   consider.

## Crafting

1. Reject arbitrary, expired, and cross-grid item tokens.
2. Reject zero, negative, and over-limit amounts.
3. Calculate a valid plan and show missing inputs without submitting it.
4. Submit one inexpensive controlled recipe.
5. Reuse the same idempotency key and confirm no duplicate craft is created.
6. Revoke craft permission between plan and submission and confirm the submit
   fails closed.
7. Confirm there is no HTTP cancellation route.

## Sidecar and browser

1. Refuse development identity mode on non-loopback listeners.
2. Reject wrong `Origin`, `Host`, audience, issuer, and expired Access tokens.
3. Confirm inbound identity headers are stripped and regenerated.
4. Confirm CSP and other secure headers are present.
5. Check dashboard keyboard navigation plus loading, stale, and offline states.
6. After publication, confirm a private browser redirects to Cloudflare Access.
