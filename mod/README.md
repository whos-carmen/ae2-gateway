# AE2 Gateway mod

Server-side Forge 1.20.1 mod for the private AE2 Gateway API. It is compiled against Forge
47.4.20 and the exact `libs/appliedenergistics2-forge-15.5.0-uelm.jar`.

## Configuration and security

On first successful server start the mod creates `config/ae2-gateway.json`, generates a
256-bit `serviceSecret`, and attempts to set the file to owner read/write only. The HTTP
listener is always bound to the JVM loopback address. Every request, including health,
must send the secret in `X-AE2-Mod-Secret`; browser clients must never receive it.
All endpoints except `/api/v1/health` also require `X-AE2-Identity-Assertion` and
`X-Request-ID` exactly as emitted by `sidecar/proxy.go`.

The identity assertion is
`base64url(JSON).base64url(HMAC-SHA256(encodedPayload))`. The JSON must contain exactly
`sub`, `email`, `iat`, `exp`, `jti`, `method`, and `path`. The mod requires an exact
30-second lifetime, binds `jti` to the 32-character lowercase hexadecimal request ID,
binds method and decoded URL path exactly, validates strict field types and lengths, and
checks the HMAC and bound strings in constant time. The default clock skew allowance is
five seconds in either direction. A bounded replay cache retains JTIs through their
accepted expiry/skew window, rejects reuse, removes expired entries, and fails closed
rather than evicting a still-valid entry.

AE2 calls run only on the Minecraft server thread. HTTP work enters a bounded queue, and
both queued tasks and snapshot/crafting maintenance use configured per-tick task/time
budgets. Responses are serialized from immutable snapshots.

`maxActivePlans` and `maxRetainedRequests` are hard map capacities.
`planRetentionSeconds` controls how long a plan remains addressable after its normal TTL,
and `requestRetentionSeconds` controls result/idempotency retention. Expired entries are
cleaned incrementally from insertion-ordered maps; new work fails closed while a capacity
is full. `maxAssertionJtis` and `assertionClockSkewSeconds` configure assertion replay and
clock bounds. Configuration validation requires request retention to be at least plan
retention.

## Commands

- `/ae2gateway enroll <alias>` — operator level 4; look directly at an exposed face of an
  AE2 node. Re-enrolling the same anchor retains its UUID and updates its alias.
- `/ae2gateway grant <grid UUID> <player UUID> read|craft`
- `/ae2gateway revoke <grid UUID> <player UUID> read|craft`
- `/ae2gateway link <code>` — run by the Minecraft player linking a dashboard identity.

The enrolling operator receives read and craft access. Craft permission implies read
when granted. Identity links, anchors, aliases, UUIDs, and ACLs are persisted in the
overworld saved data.

## Public-API limitations

- AE2 15.5.0's public `IActionSource` can represent a player only from a live `Player`.
  Planning and submission therefore fail closed while the linked player is offline.
  The mod never substitutes `IActionSource.empty()` and uses no reflection.
- Public `ICraftingCPU` exposes no persistent CPU identifier. CPU IDs in telemetry are
  opaque snapshot identifiers and must not be persisted by clients.
- AE2's public API provides a calculation `Future` but no progress callback. Plan state
  is polled within the tick budget. There is deliberately no cancellation HTTP route.
- The stored block-position/side anchor does not force chunks to load. An unloaded anchor
  reports the grid offline and is retried after the chunk is naturally loaded.

## Build

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew clean test build
```

The exact supplied AE2 artifact is a production/remapped mod. Its Mixin refmap targets
runtime SRG names, so it fails in ForgeGradle's official-mapped `runServer` userdev
environment before gateway code loads (`MinecraftServerMixin` cannot resolve its SRG
target). This is an AE2 userdev-boundary issue, not a compile error; do startup validation
with the reobfuscated gateway JAR in the copied production-style Forge server. GuideME
20.1.7 is included as a development runtime dependency because this AE2 build declares it
mandatory, but it is not bundled into the gateway JAR.

Real-server validation is still required for the custom AE2 deployment: enroll an
exposed node, restart and confirm UUID persistence, split/merge the network around the
anchor, verify unloaded anchors fail closed, exercise denied/read/craft ACLs and identity
link reuse, and submit one bounded craft while checking tick time and audit output.
