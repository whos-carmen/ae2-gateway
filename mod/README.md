# Mod

Server-side Forge 1.20.1 mod that hosts the private AE2 Gateway API. Build it
against Forge 47.4.20 and
`libs/appliedenergistics2-forge-15.5.0-uelm.jar`.

## Configure and secure the API

On first successful server start, the mod creates `config/ae2-gateway.json`,
generates a 256-bit `serviceSecret`, and sets the file to owner read/write when
possible. The HTTP listener always binds to the JVM loopback address.

Every request, including health, must send the secret in `X-AE2-Mod-Secret`.
Do not give that secret to browsers. Every endpoint except `/api/v1/health`
also requires `X-AE2-Identity-Assertion` and `X-Request-ID` exactly as the
sidecar emits them in `sidecar/proxy.go`.

The identity assertion is
`base64url(JSON).base64url(HMAC-SHA256(encodedPayload))`. The JSON must contain
exactly `sub`, `email`, `iat`, `exp`, `jti`, `method`, and `path`. The mod
requires a 30-second lifetime, binds `jti` to the 32-character lowercase hex
request ID, binds method and decoded URL path exactly, validates field types
and lengths, and checks the HMAC and bound strings in constant time. Default
clock skew is five seconds. A bounded replay cache keeps JTIs through their
accepted expiry window, rejects reuse, removes expired entries, and fails
closed instead of evicting a still-valid entry.

AE2 calls run only on the Minecraft server thread. HTTP work enters a bounded
queue. Snapshot and crafting maintenance use configured per-tick task and time
budgets. Responses are serialized from immutable snapshots.

`maxActivePlans` and `maxRetainedRequests` are hard map capacities.
`planRetentionSeconds` controls how long a plan stays addressable after its
TTL. `requestRetentionSeconds` controls result and idempotency retention.
Expired entries are cleaned from insertion-ordered maps. New work fails closed
while a capacity is full. `maxAssertionJtis` and `assertionClockSkewSeconds`
configure assertion replay and clock bounds. Request retention must be at least
plan retention.

## Commands

- `/ae2gateway enroll <alias>`: operator level 4; look at an exposed face of an
  AE2 node. Re-enrolling the same anchor keeps its UUID and updates its alias.
- `/ae2gateway grant <grid UUID> <player UUID> read|craft`
- `/ae2gateway revoke <grid UUID> <player UUID> read|craft`
- `/ae2gateway link <code>`: run by the Minecraft player who is linking a
  dashboard identity

The enrolling operator receives read and craft access. Craft permission implies
read. Identity links, anchors, aliases, UUIDs, and ACLs persist in overworld
saved data.

## Public API limits

- AE2 15.5.0 public `IActionSource` can represent a player only from a live
  `Player`. Planning and submission fail closed while the linked player is
  offline. The mod does not substitute `IActionSource.empty()` and does not use
  reflection for this.
- Public `ICraftingCPU` has no persistent CPU identifier. CPU IDs in telemetry
  are opaque snapshot IDs. Clients must not persist them.
- AE2 exposes a calculation `Future` but no progress callback. Plan state is
  polled inside the tick budget. There is no cancellation HTTP route.
- The stored block-position and side anchor does not force chunks to load. An
  unloaded anchor reports the grid offline and retries after the chunk loads
  naturally.

## Build

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew clean test build
```

The supplied AE2 artifact is a production remapped mod. Its Mixin refmap
targets runtime SRG names, so ForgeGradle official-mapped `runServer` fails
before gateway code loads. Validate startup with the reobfuscated gateway JAR
in a production-style Forge server copy. GuideME 20.1.7 is a development
runtime dependency because this AE2 build requires it; it is not bundled into
the gateway JAR.

On a real server, enroll an exposed node, restart and confirm UUID persistence,
split and merge the network around the anchor, verify unloaded anchors fail
closed, exercise denied, read, and craft ACLs plus identity link reuse, and
submit one bounded craft while you watch tick time and audit output.
