#!/usr/bin/env python3
"""Audit a Uperf Magisk ZIP without modifying production files."""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import pathlib
import re
import sys
import zipfile


RELEVANT_PATHS = (
    "module.prop",
    "bin/uperf",
    "config/sdm8g3.json",
    "config/perapp_powermode.txt",
    "script/initsvc.sh",
    "script/libpowercfg.sh",
    "script/libsysinfo.sh",
    "script/libuperf.sh",
    "script/powercfg.json",
    "script/powercfg_main.sh",
    "script/powercfg_once.sh",
    "script/setup.sh",
)

CONFLICT_PATTERNS = {
    "thermal_disable": re.compile(
        r"thermal|mi_thermald|msm_thermal|thermal-engine", re.IGNORECASE),
    "gpu_or_kgsl": re.compile(r"kgsl|adreno|gpu", re.IGNORECASE),
    "devfreq_or_bus": re.compile(r"devfreq|bus_dcvs|bw_hwm|bw_hwmon", re.IGNORECASE),
    "oem_service_stop": re.compile(
        r"\b(?:stop|killall|pkill)\b.*(?:perf|power|thermal)", re.IGNORECASE),
    "magisk_or_data_adb": re.compile(r"/data/adb|magisk", re.IGNORECASE),
    "bundled_asoul": re.compile(r"asoul|a-soul", re.IGNORECASE),
    "native_auto_or_perapp": re.compile(
        r"perapp_powermode|\bauto\b|game.*(?:list|package)", re.IGNORECASE),
    "sched_owner": re.compile(r"\bsched\b|affinity|walt", re.IGNORECASE),
}


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_module_prop(text: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def printable_strings(data: bytes, minimum: int = 5) -> list[str]:
    return [
        match.group().decode("ascii", "replace")
        for match in re.finditer(rb"[\x20-\x7e]{%d,}" % minimum, data)
    ]


def flatten(value: object, prefix: str = "") -> dict[str, object]:
    result: dict[str, object] = {}
    if isinstance(value, dict):
        for key in sorted(value):
            child = f"{prefix}.{key}" if prefix else str(key)
            result.update(flatten(value[key], child))
    elif isinstance(value, list):
        for index, child_value in enumerate(value):
            child = f"{prefix}[{index}]"
            result.update(flatten(child_value, child))
    else:
        result[prefix] = value
    return result


def config_diff(upstream: object, production: object) -> dict[str, object]:
    left = flatten(upstream)
    right = flatten(production)
    added = {key: left[key] for key in sorted(left.keys() - right.keys())}
    removed = {key: right[key] for key in sorted(right.keys() - left.keys())}
    changed = {
        key: {"upstream": left[key], "production": right[key]}
        for key in sorted(left.keys() & right.keys())
        if left[key] != right[key]
    }
    same_count = sum(
        1 for key in left.keys() & right.keys() if left[key] == right[key])
    return {
        "same_leaf_count": same_count,
        "upstream_only": added,
        "production_only": removed,
        "changed": changed,
    }


def normalized_names(archive: zipfile.ZipFile) -> dict[str, str]:
    result: dict[str, str] = {}
    for info in archive.infolist():
        name = info.filename.replace("\\", "/").lstrip("./")
        path = pathlib.PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts:
            raise ValueError(f"unsafe ZIP member: {info.filename}")
        if name.endswith("/"):
            continue
        if name in result:
            raise ValueError(f"duplicate ZIP member: {name}")
        result[name] = info.filename
    return result


def choose_previous_snapshot(repo: pathlib.Path, version: str) -> pathlib.Path | None:
    root = repo / "upstream" / "uperf"
    if not root.is_dir():
        return None
    candidates = [path for path in root.iterdir() if path.is_dir() and path.name != version]
    return sorted(candidates, key=lambda path: path.name)[-1] if candidates else None


def write_text(path: pathlib.Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write(text)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate a read-only Uperf upstream audit report.")
    parser.add_argument("--zip", required=True, dest="zip_path")
    parser.add_argument("--output", required=True)
    parser.add_argument(
        "--repo",
        default=str(pathlib.Path(__file__).resolve().parents[2]),
        help="ZuiControl repository root",
    )
    args = parser.parse_args()

    zip_path = pathlib.Path(args.zip_path).resolve()
    output = pathlib.Path(args.output).resolve()
    repo = pathlib.Path(args.repo).resolve()
    production_binary = repo / "payload/system/bin/uperf"
    production_config = repo / "payload/system/etc/zui_control/uperf-sm8650.json"
    if output == repo or repo in output.parents:
        allowed_report_root = repo / "V20_4_UPERF_ARCHITECTURE_REBASE" / "raw"
        if output != allowed_report_root and allowed_report_root not in output.parents:
            raise SystemExit("output inside repo is restricted to the Uperf raw report tree")
    if not zip_path.is_file() or not production_binary.is_file() or not production_config.is_file():
        raise SystemExit("missing ZIP or production baseline")

    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as archive:
        names = normalized_names(archive)
        missing = [name for name in RELEVANT_PATHS if name not in names]
        if missing:
            raise SystemExit("missing required ZIP members: " + ", ".join(missing))
        content = {name: archive.read(names[name]) for name in RELEVANT_PATHS}
        all_names = sorted(names)

    module = parse_module_prop(content["module.prop"].decode("utf-8", "replace"))
    raw_version = module.get("version", "unknown")
    version_match = re.search(r"v?([0-9]+(?:\.[0-9]+)+)", raw_version)
    version = version_match.group(1) if version_match else raw_version
    upstream_binary = content["bin/uperf"]
    strings = printable_strings(upstream_binary)
    embedded_versions = sorted({
        item for item in strings
        if re.fullmatch(r"v[0-9]+\([0-9.]+\)", item)
    })
    production_binary_bytes = production_binary.read_bytes()
    upstream_json = json.loads(content["config/sdm8g3.json"].decode("utf-8"))
    production_json = json.loads(production_config.read_text(encoding="utf-8"))
    semantic_diff = config_diff(upstream_json, production_json)

    audit = {
        "input_zip": {
            "filename": zip_path.name,
            "absolute_path": str(zip_path),
            "size": zip_path.stat().st_size,
            "sha256": sha256_file(zip_path),
            "file_count": len(all_names),
        },
        "module": {
            "version": raw_version,
            "normalized_version": version,
            "versionCode": module.get("versionCode", "unknown"),
        },
        "binary": {
            "upstream_sha256": sha256_bytes(upstream_binary),
            "production_sha256": sha256_bytes(production_binary_bytes),
            "byte_for_byte_equal": upstream_binary == production_binary_bytes,
            "embedded_versions": embedded_versions,
        },
        "sm8650_selection": {
            "board": "pineapple",
            "soc_model": "SM8650",
            "selected_config": "sdm8g3",
        },
        "production_modified": False,
    }
    write_text(output / "UPSTREAM_AUDIT.json", json.dumps(audit, indent=2, ensure_ascii=False) + "\n")
    write_text(output / "SM8650_CONFIG_DIFF.json", json.dumps(semantic_diff, indent=2, ensure_ascii=False) + "\n")

    previous = choose_previous_snapshot(repo, version)
    script_diffs: dict[str, object] = {}
    for name in RELEVANT_PATHS:
        if not name.startswith("script/") and name != "config/perapp_powermode.txt":
            continue
        previous_path = previous / pathlib.PurePosixPath(name) if previous else None
        new_text = content[name].decode("utf-8", "replace").splitlines()
        old_text = (
            previous_path.read_text(encoding="utf-8", errors="replace").splitlines()
            if previous_path and previous_path.is_file() else []
        )
        script_diffs[name] = {
            "sha256": sha256_bytes(content[name]),
            "previous_snapshot": str(previous_path) if previous_path else None,
            "unified_diff": list(difflib.unified_diff(
                old_text, new_text,
                fromfile=str(previous_path) if previous_path else "/dev/null",
                tofile=f"ZIP/{name}", lineterm="")),
        }
    write_text(output / "RELEVANT_SCRIPT_DIFF.json", json.dumps(script_diffs, indent=2, ensure_ascii=False) + "\n")

    scan_text = "\n".join(
        f"## {name}\n{content[name].decode('utf-8', 'replace')}"
        for name in RELEVANT_PATHS if name.endswith((".sh", ".json", ".txt", ".prop"))
    )
    conflicts = {
        name: sorted({line.strip() for line in scan_text.splitlines() if pattern.search(line)})
        for name, pattern in CONFLICT_PATTERNS.items()
    }
    conflict_lines = [
        "# Owner-conflict report",
        "",
        "This report is generated from the ZIP only; it does not authorize adoption.",
        "",
    ]
    for name, matches in conflicts.items():
        conflict_lines.extend([f"## {name}", ""])
        if matches:
            conflict_lines.extend(f"- `{line[:240]}`" for line in matches[:40])
        else:
            conflict_lines.append("- No lexical match.")
        conflict_lines.append("")
    write_text(output / "OWNER_CONFLICT_REPORT.md", "\n".join(conflict_lines))

    changed_count = len(semantic_diff["changed"])
    candidates = [
        "# Adoption candidates",
        "",
        f"- Upstream module: `{raw_version}` / versionCode `{module.get('versionCode', 'unknown')}`.",
        f"- Binary changed: `{str(upstream_binary != production_binary_bytes).lower()}`.",
        f"- SM8650 changed leaves versus production: `{changed_count}`.",
        "- Candidate scope: CPU/power-model, preset, input and SF-analysis fields only.",
        "- Manual review required: every changed leaf and every owner-conflict match.",
        "- Never auto-adopt: Native Auto/perapp, sched, GPU, thermal, OEM-service control, Magisk paths or bundled apps/binaries.",
        "- Production files were not modified.",
        "",
    ]
    write_text(output / "ADOPTION_CANDIDATES.md", "\n".join(candidates))
    print(json.dumps(audit, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
