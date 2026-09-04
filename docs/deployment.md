# Deployment

Use this runbook only after you approve a production change. Do not run these
steps against `star-tech-2` without that approval.

## Artifacts

`./scripts/package.sh` produces:

- remapped Forge mod JAR
- Linux amd64 sidecar binary
- dashboard asset archive
- SHA-256 checksums
- Cloudflare ingress template
- systemd unit template

## Before you deploy

1. Take a server backup and verify that you can restore it.
2. Record the current mod list, config files, and Java launch command.
3. Confirm local integration tests passed against a copied world.
4. Create the Cloudflare Access application before you start the tunnel
   connector.
5. Generate independent random secrets for the mod and the sidecar.
6. Confirm that the mod API and Minecraft ports are not public.

## Deploy

1. Stop Minecraft cleanly and confirm the process exited.
2. Install the new mod JAR and protected mod config.
3. Start Minecraft without Cloudflare publication. Check logs and the private
   health endpoint from the host.
4. Install the sidecar under an unprivileged account and verify it on
   loopback.
5. Start the named Tunnel connector with only its connector credential.
6. Test Access allow and deny paths, then complete a read-only dashboard check
   before you enable craft ACLs.

## Roll back

1. Stop the Tunnel connector and sidecar.
2. Stop Minecraft cleanly.
3. Remove the new mod JAR and restore the previous config.
4. Restore the world backup only if the new mod wrote world data that the
   previous build cannot read.
5. Start Minecraft and confirm the previous mod list and world.

The gateway stores namespaced saved data and does not rewrite AE2 blocks. Keep
a backup before the first production install anyway.
