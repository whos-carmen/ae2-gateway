# Deployment and rollback runbook

This document is preparation only. Do not execute these steps against
`star-tech-2` without explicit approval.

## Prepared artifacts

The packaging task produces:

- remapped Forge mod JAR
- Linux amd64 dashboard sidecar
- SHA-256 checksum manifest
- Cloudflare ingress template
- systemd service template

## Pre-deployment checks

1. Take and verify a completed server backup.
2. Record the existing mod list, configuration and Java process command.
3. Confirm the local integration test passed against the copied world.
4. Create the Access application before enabling the tunnel route.
5. Provision independent random mod-service and sidecar secrets.
6. Confirm no service binds the mod API or Minecraft port publicly.

## Planned production sequence

1. Stop Minecraft cleanly and verify the process exited.
2. Copy the new mod JAR and protected mod configuration.
3. Start Minecraft without publishing Cloudflare; verify logs and the private
   health endpoint from the host.
4. Install the sidecar under a dedicated unprivileged account and verify it on
   loopback.
5. Start the named Tunnel connector using only its connector credential.
6. Test Access allow and deny behavior, then perform a read-only dashboard
   check before enabling craft ACLs.

## Rollback

1. Stop the Tunnel connector and sidecar.
2. Stop Minecraft cleanly.
3. Remove the new mod JAR and restore the pre-deployment configuration.
4. Restore the world backup only if the mod changed persistent world data in a
   way the previous server cannot read.
5. Start Minecraft and verify the original mod list and world.

The gateway uses namespaced saved data and does not modify AE2 blocks, but a
backup remains mandatory before first production use.
