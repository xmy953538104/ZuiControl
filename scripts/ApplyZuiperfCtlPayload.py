#!/usr/bin/env python3
import argparse
import hashlib
import json
import pathlib
import shutil
from datetime import datetime


def stamp():
    return datetime.now().strftime("%Y%m%d_%H%M%S")


def resolve_root():
    return pathlib.Path(__file__).resolve().parents[1]


def resolve_unpack(root, unpack_arg):
    candidates = []
    if unpack_arg:
        candidates.append(pathlib.Path(unpack_arg))
    candidates.append(root / "work" / "unpack")
    for candidate in candidates:
        candidate = candidate.expanduser().resolve()
        if any((candidate / name).exists() for name in ["system_a", "vendor_a", "product_a", "odm_a"]):
            return candidate
    raise SystemExit("Cannot find unpack root. Expected work/unpack with system_a.")


def ensure_line(path, key, line, changed, dry_run):
    path = pathlib.Path(path)
    lines = []
    if path.exists():
        lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
    kept = [item for item in lines if first_field(item) != key]
    if line in kept:
        return
    kept.append(line)
    changed.append(str(path))
    if dry_run:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(kept) + "\n", encoding="utf-8")


def first_field(line):
    parts = line.split()
    return parts[0] if parts else ""


def file_context_regex(path):
    return "/" + path.replace("\\", "/").replace(".", r"\.")


def mode_for(rel, is_dir):
    if is_dir:
        return "0755"
    if rel in ["system_a/system/bin/zui_perfctld", "system_a/system/bin/AsoulOpt"]:
        return "0755"
    return "0644"


def owner_group_for(rel):
    if rel == "system_a/system/bin/AsoulOpt":
        return "0 2000"
    return "0 0"


def context_for(rel):
    if rel == "system_a/system/bin/AsoulOpt":
        return "u:object_r:performanced_exec:s0"
    return "u:object_r:system_file:s0"


def copy_payload(payload, unpack, dry_run, report):
    copied = []
    metadata = []
    dst_items = []
    for src in sorted(payload.rglob("*")):
        rel_payload = src.relative_to(payload).as_posix()
        if not rel_payload.startswith("system/"):
            continue
        dst_rel = f"system_a/{rel_payload}"
        dst = unpack / dst_rel
        mode = int(mode_for(dst_rel, src.is_dir()), 8)
        dst_items.append((dst_rel, src.is_dir()))
        if src.is_dir():
            if not dry_run:
                dst.mkdir(parents=True, exist_ok=True)
                try:
                    dst.chmod(mode)
                except OSError:
                    pass
            copied.append({"source": str(src), "target": dst_rel, "directory": True})
            continue
        changed = True
        if dst.exists() and dst.is_file():
            changed = src.read_bytes() != dst.read_bytes()
        copied.append({"source": str(src), "target": dst_rel, "changed": changed})
        if not dry_run:
            dst.parent.mkdir(parents=True, exist_ok=True)
            if changed:
                shutil.copy2(src, dst)
            try:
                dst.chmod(mode)
            except OSError:
                pass

    for rel, is_dir in dst_items:
        mode = mode_for(rel, is_dir)
        metadata.append(("fs", "system_a", rel, f"{rel} {owner_group_for(rel)} {mode}"))
        metadata.append(("ctx", "system_a", file_context_regex(rel), f"{file_context_regex(rel)} {context_for(rel)}"))

    report["copied"] = copied
    report["metadata_entries"] = len(metadata)
    return metadata


def update_metadata(root, unpack, entries, dry_run, report):
    changed = []
    for kind, base, key, line in entries:
        suffix = "_fs_config" if kind == "fs" else "_file_contexts"
        ensure_line(unpack / "config" / f"{base}{suffix}", key, line, changed, dry_run)
        ensure_line(root / "work" / "config" / "erofs_overrides" / f"{base}{suffix}", key, line, changed, dry_run)
    report["metadata_files"] = changed


def read_patch_lines(path):
    if not path.exists():
        return []
    return [
        line.rstrip("\n")
        for line in path.read_text(encoding="utf-8", errors="ignore").splitlines()
        if line.strip()
    ]


def append_unique_lines(target, lines, dry_run):
    if not lines:
        return []
    target = pathlib.Path(target)
    current = []
    if target.exists():
        current = target.read_text(encoding="utf-8", errors="ignore").splitlines()
    additions = [line for line in lines if line not in current]
    if additions and not dry_run:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("\n".join(current + additions) + "\n", encoding="utf-8")
    return additions


def patch_property_contexts(unpack, payload, dry_run, report):
    patch = payload / "patches" / "plat_property_contexts_add.txt"
    target = unpack / "system_a" / "system" / "etc" / "selinux" / "plat_property_contexts"
    additions = append_unique_lines(target, read_patch_lines(patch), dry_run)
    report["property_contexts_added"] = additions


def patch_plat_sepolicy(unpack, payload, dry_run, report):
    patch = payload / "patches" / "plat_sepolicy_zui_perfctl_init_mount.cil"
    target = unpack / "system_a" / "system" / "etc" / "selinux" / "plat_sepolicy.cil"
    patch_lines = read_patch_lines(patch)
    additions = append_unique_lines(target, patch_lines, dry_run)
    report["plat_sepolicy_added"] = additions
    if patch_lines and not dry_run:
        update_plat_mapping_hash(unpack, report)


def patch_vendor_sepolicy(unpack, payload, dry_run, report):
    patch = payload / "patches" / "vendor_sepolicy_zui_perfctl_kgsl.cil"
    target = unpack / "vendor_a" / "etc" / "selinux" / "vendor_sepolicy.cil"
    additions = append_unique_lines(target, read_patch_lines(patch), dry_run)
    report["vendor_sepolicy_added"] = additions


def update_plat_mapping_hash(unpack, report):
    sepolicy_dir = unpack / "system_a" / "system" / "etc" / "selinux"
    plat = sepolicy_dir / "plat_sepolicy.cil"
    mapping = sepolicy_dir / "mapping" / "34.0.cil"
    sha_file = sepolicy_dir / "plat_sepolicy_and_mapping.sha256"
    digest = hashlib.sha256(plat.read_bytes() + mapping.read_bytes()).hexdigest()
    old = sha_file.read_text(encoding="utf-8", errors="ignore").strip() if sha_file.exists() else ""
    sha_file.write_text(digest + "\n", encoding="utf-8")
    report["plat_sepolicy_and_mapping_sha256"] = {
        "old": old,
        "new": digest,
        "odm_precompiled_hash_left_unchanged": True,
        "reason": "Force Android init to compile split sepolicy with /system/bin/secilc on boot.",
    }


def main():
    parser = argparse.ArgumentParser(description="Apply ZuiperfCtl v1 payload into an unpacked image tree.")
    parser.add_argument("--root", help="Project root containing work/config, default: this repository root")
    parser.add_argument("--unpack", help="Unpacked image root, default: work/unpack")
    parser.add_argument("--payload", help="Payload root, default: payload")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    root = pathlib.Path(args.root).resolve() if args.root else resolve_root()
    unpack = resolve_unpack(root, args.unpack)
    payload = pathlib.Path(args.payload).resolve() if args.payload else root / "payload"
    if not payload.exists():
        raise SystemExit(f"Missing payload: {payload}")

    report = {
        "started_at": datetime.now().isoformat(timespec="seconds"),
        "root": str(root),
        "unpack": str(unpack),
        "payload": str(payload),
        "dry_run": args.dry_run,
        "warnings": [],
    }

    apk = payload / "system" / "priv-app" / "ZuiperfCtl" / "ZuiperfCtl.apk"
    if not apk.exists():
        report["warnings"].append("ZuiperfCtl.apk is missing. Run scripts/BuildZuiperfCtl.ps1 before packing a bootable image with the app.")

    entries = copy_payload(payload, unpack, args.dry_run, report)
    patch_property_contexts(unpack, payload, args.dry_run, report)
    patch_plat_sepolicy(unpack, payload, args.dry_run, report)
    patch_vendor_sepolicy(unpack, payload, args.dry_run, report)
    update_metadata(root, unpack, entries, args.dry_run, report)

    out_dir = root / "work" / "config"
    out_name = f"zui_perfctl_payload_{stamp()}.json"
    if not args.dry_run:
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / out_name).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        (out_dir / "zui_perfctl_payload_latest.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
