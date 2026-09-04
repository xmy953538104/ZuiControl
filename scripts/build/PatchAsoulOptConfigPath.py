#!/usr/bin/env python3
"""Patch the stripped A-SOUL binary's fixed config path without changing size."""

from __future__ import annotations

import argparse
from pathlib import Path


OLD_PATH = b"/data/adb/naki/asopt.conf"
NEW_PATH = b"/data/vendor/asopt.conf"


def patch_binary(path: Path) -> None:
    data = path.read_bytes()
    if data.count(NEW_PATH) == 1 and data.count(OLD_PATH) == 0:
        return
    if data.count(OLD_PATH) != 1 or data.count(NEW_PATH) != 0:
        raise ValueError("unexpected A-SOUL config-path occurrences")
    if len(NEW_PATH) > len(OLD_PATH):
        raise ValueError("replacement path does not fit the existing ELF string")
    replacement = NEW_PATH + b"\0" * (len(OLD_PATH) - len(NEW_PATH))
    patched = data.replace(OLD_PATH, replacement, 1)
    if len(patched) != len(data):
        raise ValueError("binary size changed")
    path.write_bytes(patched)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("binary", type=Path)
    args = parser.parse_args()
    patch_binary(args.binary)


if __name__ == "__main__":
    main()
