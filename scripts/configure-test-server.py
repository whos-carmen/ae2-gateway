#!/usr/bin/env python3
from __future__ import annotations

import os
import re
import sys
from pathlib import Path


def update_properties(path: Path, updates: dict[str, str]) -> None:
    lines = path.read_text().splitlines()
    seen: set[str] = set()
    output: list[str] = []
    for line in lines:
        if "=" not in line or line.lstrip().startswith("#"):
            output.append(line)
            continue
        key = line.split("=", 1)[0]
        if key in updates:
            output.append(f"{key}={updates[key]}")
            seen.add(key)
        else:
            output.append(line)
    for key, value in updates.items():
        if key not in seen:
            output.append(f"{key}={value}")
    path.write_text("\n".join(output) + "\n")


def update_heap(path: Path) -> None:
    text = path.read_text()
    text = re.sub(r"(?m)^\s*-Xm[xs]\S+\s*$", "", text)
    path.write_text(text.rstrip() + "\n-Xms4G\n-Xmx12G\n")


def disable_old_gateway(mods: Path) -> None:
    disabled = mods / ".disabled"
    disabled.mkdir(exist_ok=True)
    for jar in mods.glob("ae2webintegration-*.jar"):
        target = disabled / jar.name
        if target.exists():
            jar.unlink()
        else:
            jar.rename(target)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: configure-test-server.py TEST_DIR")
    root = Path(sys.argv[1]).expanduser().resolve()
    if root == Path("/") or root == Path.home():
        raise SystemExit("refusing unsafe test-server path")

    update_properties(
        root / "server.properties",
        {
            "server-ip": "127.0.0.1",
            "server-port": "25566",
            "query.port": "25566",
            "rcon.port": "25576",
            "enable-query": "false",
            "enable-rcon": "false",
            "online-mode": "true",
            "max-players": "4",
            "motd": "AE2 Gateway Local Test",
        },
    )
    update_heap(root / "user_jvm_args.txt")
    disable_old_gateway(root / "mods")

    for directory, mode in ((root, 0o700), (root / "config", 0o700)):
        os.chmod(directory, mode)
    for sensitive in (
        root / "config/ae2webintegration/ae2webintegration.toml",
        root / "config/ae2webintegration/webdata.json",
    ):
        if sensitive.exists():
            os.chmod(sensitive, 0o600)


if __name__ == "__main__":
    main()
