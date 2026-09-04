#!/usr/bin/env python3
"""Validate fixed-seven physical read-back evidence; a qdl flag alone is never proof."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


LABELS = (
    "super",
    "vbmeta_system_a",
    "vbmeta_system_b",
    "boot_a",
    "boot_b",
    "vbmeta_a",
    "vbmeta_b",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(package: Path, manifest_path: Path) -> dict[str, object]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    if manifest.get("qdl_flag_only_accepted_as_proof") is not False:
        raise ValueError("qdl --read-back-verify flag alone cannot satisfy this gate")
    if manifest.get("result") != "PHYSICAL_PARTITION_READBACK=PASS":
        raise ValueError("physical read-back PASS marker is absent")
    if "dump-part" not in manifest.get("method", ""):
        raise ValueError("manifest does not identify host-visible dump-part evidence")

    programs = ET.parse(package / "rawprogram_zuicontrol.xml").getroot().findall("program")
    if tuple(item.attrib.get("label") for item in programs) != LABELS:
        raise ValueError("rawprogram is not the ordered fixed-seven allowlist")
    evidence = manifest.get("fixed_seven")
    if not isinstance(evidence, list) or tuple(item.get("label") for item in evidence) != LABELS:
        raise ValueError("manifest does not cover every ordered fixed-seven partition")

    results = []
    for program, item in zip(programs, evidence):
        image = package / program.attrib["filename"]
        expected_bytes = int(program.attrib["num_partition_sectors"]) * int(
            program.attrib["SECTOR_SIZE_IN_BYTES"]
        )
        image_hash = sha256(image)
        if image.stat().st_size != expected_bytes:
            raise ValueError(f"{item['label']}: image does not fill the programmed extent")
        if item.get("result") != "PASS":
            raise ValueError(f"{item['label']}: physical read-back result is not PASS")
        if int(item.get("physical_partition_number", -1)) != int(
            program.attrib["physical_partition_number"]
        ):
            raise ValueError(f"{item['label']}: UFS LUN mismatch")
        if int(item.get("bytes", -1)) != expected_bytes:
            raise ValueError(f"{item['label']}: physical byte count mismatch")
        if item.get("source_sha256") != image_hash:
            raise ValueError(f"{item['label']}: source image hash mismatch")
        if item.get("physical_readback_sha256") != image_hash:
            raise ValueError(f"{item['label']}: physical partition hash mismatch")
        results.append({"label": item["label"], "bytes": expected_bytes, "sha256": image_hash})
    return {"ok": True, "proof": "HOST_VISIBLE_PHYSICAL_BYTES_AND_SHA256", "fixed_seven": results}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True)
    parser.add_argument("--manifest", required=True)
    args = parser.parse_args()
    try:
        result = verify(Path(args.package), Path(args.manifest))
    except (OSError, ValueError, KeyError, ET.ParseError, json.JSONDecodeError) as exc:
        print(f"PHYSICAL_PARTITION_READBACK_VERIFY=FAIL: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    print("PHYSICAL_PARTITION_READBACK_VERIFY=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
