#!/usr/bin/env python3
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import zipfile
from datetime import datetime


APP_PACKAGE = "com.zui.zuicontrol"
LEGACY_APP_PACKAGE = "com.zui.zuiperfctl"
APP_APK_PATH = "system/priv-app/ZuiControlV58/ZuiControl.apk"
LEGACY_APP_PAYLOAD_PATH = "system/priv-app/ZuiControl"

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def stamp():
    return datetime.now().strftime("%Y%m%d_%H%M%S")


def resolve_root():
    return pathlib.Path(__file__).resolve().parents[2]


def resolve_unpack(root, unpack_arg):
    if not unpack_arg:
        raise SystemExit("An explicit --unpack directory is required; no candidate discovery.")
    candidate = pathlib.Path(unpack_arg).resolve()
    if not (candidate / "system_a" / "system").is_dir():
        raise SystemExit("Explicit unpack root has no system_a/system.")
    return candidate


def resolve_image_root(root, unpack):
    unpack = pathlib.Path(unpack).resolve()
    if unpack.name == "unpack" and unpack.parent.name == "work":
        return unpack.parent.parent
    return root


def write_text_lf(path, text):
    path = pathlib.Path(path)
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write(text)


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
    write_text_lf(path, "\n".join(kept) + "\n")


def first_field(line):
    parts = line.split()
    return parts[0] if parts else ""


def file_context_regex(path):
    return "/" + path.replace("\\", "/").replace(".", r"\.")


def mode_for(rel, is_dir):
    if is_dir:
        return "0755"
    if rel in [
        "system_a/system/bin/zui_controld",
        "system_a/system/bin/zui_uperf_service",
        "system_a/system/bin/zui_uperf_supervisor",
        "system_a/system/bin/uperf",
        "system_a/system/bin/ZUIopt",
    ]:
        return "0755"
    return "0644"


def owner_group_for(rel):
    if rel in [
        "system_a/system/bin/zui_uperf_service",
        "system_a/system/bin/zui_uperf_supervisor",
        "system_a/system/bin/uperf",
    ]:
        return "0 2000"
    return "0 0"


def context_for(rel):
    if rel == "system_a/system/bin/ZUIopt":
        return "u:object_r:zuiopt_exec:s0"
    if rel == "system_a/system/etc/zuiopt" or rel.startswith("system_a/system/etc/zuiopt/"):
        return "u:object_r:zuiopt_config_file:s0"
    if rel in [
        "system_a/system/bin/zui_uperf_service",
        "system_a/system/bin/zui_uperf_supervisor",
        "system_a/system/bin/uperf",
    ]:
        return "u:object_r:performanced_exec:s0"
    return "u:object_r:system_file:s0"


def remove_path(path, dry_run, removed):
    path = pathlib.Path(path)
    if not path.exists():
        return
    removed.append(str(path))
    if dry_run:
        return
    if path.is_dir():
        shutil.rmtree(path)
    else:
        path.unlink()


def remove_lines_containing(path, needles, dry_run):
    path = pathlib.Path(path)
    if not path.exists():
        return []
    lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
    kept = [line for line in lines if not any(needle in line for needle in needles)]
    removed = [line for line in lines if any(needle in line for needle in needles)]
    if removed and not dry_run:
        write_text_lf(path, "\n".join(kept) + "\n")
    return removed


def remove_reviewed_oem_preinstall(unpack, dry_run, report):
    # Existing product composition excludes this original preinstall.
    removed = []
    remove_path(unpack / "system_a/system/preinstall/QQMusic", dry_run, removed)
    report["oem_preinstall_removed"] = removed


def remove_reviewed_oem_metadata(image_root, unpack, dry_run, report):
    targets = [
        unpack / "config" / "system_a_fs_config",
        unpack / "config" / "system_a_file_contexts",
        image_root / "work/config/erofs_overrides/system_a_fs_config",
        image_root / "work/config/erofs_overrides/system_a_file_contexts",
    ]
    report["oem_metadata_removed"] = {
        str(path): remove_lines_containing(path, ["system_a/system/preinstall/QQMusic"], dry_run)
        for path in targets
    }


def copy_payload(payload, unpack, dry_run, report):
    copied = []
    metadata = []
    dst_items = []
    for src in sorted(payload.rglob("*")):
        rel_payload = src.relative_to(payload).as_posix()
        if not rel_payload.startswith("system/"):
            continue
        if rel_payload == LEGACY_APP_PAYLOAD_PATH or rel_payload.startswith(LEGACY_APP_PAYLOAD_PATH + "/"):
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


def cleanup_retired_whitelists(unpack, dry_run, report):
    base = unpack / "system_a" / "system" / "etc"
    targets = [
        base / "ZuiMemCleanerConfig.xml",
        base / "ZuiPowerPolicyConfig.xml",
        base / "motorola" / "bgintents" / "com.zui.safecenter.autorun.xml",
        base / "zuipp_powercfg.xml",
    ]
    changed = []
    for path in targets:
        if not path.exists():
            continue
        lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
        kept = [
            line for line in lines
            if APP_PACKAGE not in line and LEGACY_APP_PACKAGE not in line
        ]
        if kept != lines:
            changed.append(str(path))
            if not dry_run:
                write_text_lf(path, "\n".join(kept) + "\n")
    report["retired_whitelists_removed"] = changed


def read_patch_lines(path):
    if not path.exists():
        return []
    return [
        line.rstrip("\n").lstrip("\ufeff")
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
        write_text_lf(target, "\n".join(current + additions) + "\n")
    return additions


def merge_unique_lines_by_key(target, lines, dry_run):
    if not lines:
        return [], False
    target = pathlib.Path(target)
    current = []
    if target.exists():
        current = target.read_text(encoding="utf-8", errors="ignore").splitlines()
    keys = {first_field(line) for line in lines}
    kept = [line for line in current if first_field(line) not in keys]
    updated = kept + lines
    additions = [line for line in lines if line not in current]
    changed = updated != current
    if changed and not dry_run:
        target.parent.mkdir(parents=True, exist_ok=True)
        write_text_lf(target, "\n".join(updated) + "\n")
    return additions, changed


def patch_property_contexts(unpack, payload, dry_run, report):
    patch = payload / "patches" / "plat_property_contexts_add.txt"
    target = unpack / "system_a" / "system" / "etc" / "selinux" / "plat_property_contexts"
    additions = append_unique_lines(target, read_patch_lines(patch), dry_run)
    report["property_contexts_added"] = additions


def patch_service_contexts(unpack, payload, dry_run, report):
    patch = payload / "patches" / "plat_service_contexts_add.txt"
    target = unpack / "system_a" / "system" / "etc" / "selinux" / "plat_service_contexts"
    additions = append_unique_lines(target, read_patch_lines(patch), dry_run)
    report["service_contexts_added"] = additions


def patch_file_contexts(unpack, payload, dry_run, report):
    patch = payload / "patches" / "plat_file_contexts_add.txt"
    target = unpack / "system_a" / "system" / "etc" / "selinux" / "plat_file_contexts"
    removed = remove_lines_containing(target, [
        "/data/vendor/zui_control/zuipp/active/game_policy\\.xml",
        "/data/vendor/zui_control/zuipp/active/performanceconfig\\.xml",
    ], dry_run)
    additions, changed = merge_unique_lines_by_key(target, read_patch_lines(patch), dry_run)
    report["file_contexts_removed"] = removed
    report["file_contexts_added"] = additions
    report["file_contexts_reordered_or_replaced"] = changed


def patch_plat_sepolicy(unpack, payload, dry_run, report):
    patch = payload / "patches" / "plat_sepolicy_zui_control.cil"
    target = unpack / "system_a" / "system" / "etc" / "selinux" / "plat_sepolicy.cil"
    obsolete = [
        ";; P2 XML reload only signals ZuiPP/GameHelper after active XML is remounted.",
        "(allow shell self (capability (kill)))",
        "(allow shell system_app (process (signal sigkill)))",
        "(allow shell platform_app (process (signal sigkill)))",
        "(allow init shell_data_file (dir (getattr open read search write add_name remove_name)))",
        "(allow init shell_data_file (file (getattr open read write create unlink setattr relabelfrom)))",
        "(allow performanced adb_data_file (dir (search)))",
    ]
    removed = remove_lines_containing(target, obsolete, dry_run)
    patch_lines = read_patch_lines(patch)
    additions = append_unique_lines(target, patch_lines, dry_run)
    report["plat_sepolicy_removed"] = removed
    report["plat_sepolicy_added"] = additions
    if (patch_lines or removed) and not dry_run:
        update_plat_mapping_hash(unpack, report)


def patch_vendor_sepolicy(unpack, payload, dry_run, report):
    target = unpack / "vendor_a" / "etc" / "selinux" / "vendor_sepolicy.cil"
    obsolete = [
        ";; Allow the root shell-domain daemon to enforce KGSL GPU limits.",
        ";; The daemon writes max_pwrlevel/min_pwrlevel only when a foreground app has",
        ";; an explicit ZuiControl performance profile.",
        "(allow shell_34_0 vendor_sysfs_kgsl (dir ",
        "(allow shell_34_0 vendor_sysfs_kgsl (file ",
        "(allow shell_34_0 vendor_sysfs_kgsl (lnk_file ",
        "(allow performanced_34_0 vendor_sysfs_kgsl (dir ",
        "(allow performanced_34_0 vendor_sysfs_kgsl (file ",
        "(allow performanced_34_0 vendor_sysfs_kgsl (lnk_file ",
    ]
    removed = remove_lines_containing(target, obsolete, dry_run)
    patch = payload / "patches" / "vendor_sepolicy_zui_scheduler.cil"
    additions = append_unique_lines(target, read_patch_lines(patch), dry_run)
    report["vendor_sepolicy_removed"] = removed
    report["vendor_sepolicy_added"] = additions


def update_plat_mapping_hash(unpack, report):
    sepolicy_dir = unpack / "system_a" / "system" / "etc" / "selinux"
    plat = sepolicy_dir / "plat_sepolicy.cil"
    mapping = sepolicy_dir / "mapping" / "34.0.cil"
    sha_file = sepolicy_dir / "plat_sepolicy_and_mapping.sha256"
    digest = hashlib.sha256(plat.read_bytes() + mapping.read_bytes()).hexdigest()
    old = sha_file.read_text(encoding="utf-8", errors="ignore").strip() if sha_file.exists() else ""
    write_text_lf(sha_file, digest + "\n")
    report["plat_sepolicy_and_mapping_sha256"] = {
        "old": old,
        "new": digest,
        "odm_precompiled_hash_left_unchanged": True,
        "reason": "Force Android init to compile split sepolicy with /system/bin/secilc on boot.",
    }


def preserved_framework(root, manifest_path):
    """Route A: exact Golden containers plus unchanged framework source, checked before writes."""
    if not manifest_path:
        raise SystemExit("ROUTE_A_PRODUCTION_HARD_FAIL: --preserve-framework-manifest is required.")
    manifest_path = pathlib.Path(manifest_path).resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    baseline = "29f23f8d590b88f0d472c12373366a9ef14e8330"
    expected = {
        "framework.jar": "b5f57d62546569b9bd9ba34d8757678da000c33f876358dd93a4dc747b8f1b32",
        "services.jar": "245b4f2c55d5ed8b99ecba8bd473d1d76eb40c55d67116a477299cc9d8b62000",
    }
    if manifest.get("golden_source_commit") != baseline or set(manifest.get("jars", {})) != set(expected):
        raise SystemExit("ROUTE_A_PRODUCTION_HARD_FAIL: Golden framework manifest identity.")
    git = shutil.which("git")
    if not git:
        raise SystemExit("Git is required to bind the unchanged framework source.")
    head = subprocess.check_output([git, "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    if manifest.get("production_source_commit") != head:
        raise SystemExit("ROUTE_A_PRODUCTION_HARD_FAIL: production source commit.")
    subprocess.run([git, "-C", str(root), "diff", "--exit-code", baseline, "--", "framework_patch"], check=True)
    if subprocess.check_output([git, "-C", str(root), "ls-files", "--others", "--exclude-standard", "--", "framework_patch"]).strip():
        raise SystemExit("ROUTE_A_PRODUCTION_HARD_FAIL: untracked framework source.")
    result = {}
    for name, digest in expected.items():
        entry = manifest["jars"][name]
        path = pathlib.Path(entry["path"])
        if not path.is_absolute() or path.is_symlink() or entry["sha256"] != digest:
            raise SystemExit("ROUTE_A_PRODUCTION_HARD_FAIL: Golden JAR binding.")
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != digest or len(data) != entry["size"]:
            raise SystemExit("ROUTE_A_PRODUCTION_HARD_FAIL: Golden JAR bytes.")
        result[name] = data
    return result, hashlib.sha256(manifest_path.read_bytes()).hexdigest()


def terminal_framework(root, manifest_path):
    """Current CI extension only; every other Golden JAR member must be exact."""
    path = pathlib.Path(manifest_path)
    manifest = json.loads(path.read_text(encoding="utf8"))
    git = shutil.which("git")
    if not git:
        raise SystemExit("Git required for terminal source binding")
    head = subprocess.check_output([git, "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    if manifest.get("source_commit") != head or manifest.get("schema") != "ZUIOPT_TERMINAL_FRAMEWORK_V1":
        raise SystemExit("Terminal framework source/schema mismatch")
    if subprocess.check_output([git, "-C", str(root), "status", "--porcelain"]):
        raise SystemExit("Terminal framework requires clean exact source")
    if not str(manifest.get("ci_run", "")).isdigit():
        raise SystemExit("Terminal framework CI identity required")
    def bound(entry):
        file = pathlib.Path(entry["path"])
        if not file.is_absolute() or not file.is_file() or file.is_symlink():
            raise SystemExit("Unsafe terminal artifact input")
        data = file.read_bytes()
        if hashlib.sha256(data).hexdigest() != entry["sha256"] or len(data) != entry["size"]:
            raise SystemExit("Terminal artifact hash/size mismatch")
        return data
    result = {name: bound(entry) for name, entry in manifest["jars"].items()}
    if set(result) != {"framework.jar", "services.jar"} or hashlib.sha256(result["framework.jar"]).hexdigest() != "b5f57d62546569b9bd9ba34d8757678da000c33f876358dd93a4dc747b8f1b32":
        raise SystemExit("Unexpected framework container change")
    old = bound(manifest["golden_services"])
    if hashlib.sha256(old).hexdigest() != "245b4f2c55d5ed8b99ecba8bd473d1d76eb40c55d67116a477299cc9d8b62000":
        raise SystemExit("Unapproved services baseline")
    extension = bound(manifest["ci_services_extension"])
    import io
    def members(data):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            if len(archive.namelist()) != len(set(archive.namelist())) or archive.testzip() is not None:
                raise SystemExit("Invalid services ZIP members")
            return {name: archive.read(name) for name in archive.namelist()}
    before, after = members(old), members(result["services.jar"])
    if set(before) != set(after) or after.get("classes4.dex") != extension:
        raise SystemExit("Terminal services extension identity mismatch")
    if before["classes4.dex"] == extension or any(before[name] != after[name] for name in before if name != "classes4.dex"):
        raise SystemExit("Unexplained services member delta")
    return result, hashlib.sha256(path.read_bytes()).hexdigest()


def require_original_base(unpack, manifest_path):
    """Reject patched candidates: exact approved original decoded path/content set."""
    data = pathlib.Path(manifest_path).read_bytes()
    if hashlib.sha256(data).hexdigest() != "62044151e1173be82aef669138fbf02b564c2293b7c30b2f89eb44f3d52bbc65":
        raise SystemExit("Unapproved ORIGINAL_072 manifest")
    original = json.loads(data)
    for part, entries in original.items():
        base = unpack / part
        actual = {"/"} | {"/" + p.relative_to(base).as_posix() for p in base.rglob("*")}
        if actual != set(entries):
            raise SystemExit("ORIGINAL_072 path set mismatch: " + part)
        for name, row in entries.items():
            path = base / name.lstrip("/")
            if path.is_symlink():
                raise SystemExit("Unexpected host symlink in original extraction")
            if row["type"] == "DIR":
                if not path.is_dir():
                    raise SystemExit("Original directory mismatch: " + name)
                continue
            if not path.is_file():
                raise SystemExit("Original file missing: " + name)
            if row["type"] == "LINK":
                content = path.read_bytes()
                if not content.startswith(b"!<symlink>") or content[10:].decode("utf16").rstrip("\0") != row["symlink_target"]:
                    raise SystemExit("Original link mismatch: " + name)
            else:
                digest = hashlib.sha256()
                with path.open("rb") as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(block)
                if path.stat().st_size != row["bytes"] or digest.hexdigest() != row["sha256"]:
                    raise SystemExit("Original file bytes mismatch: " + name)
    return "PASS_EXACT_ORIGINAL_072_PATHS_AND_CONTENT"


def main():
    parser = argparse.ArgumentParser(description="Apply ZuiControl payload into an unpacked image tree.")
    parser.add_argument("--root", help="Project root containing work/config, default: this repository root")
    parser.add_argument("--unpack", help="Unpacked image root, default: work/unpack")
    parser.add_argument("--payload", help="Payload root, default: payload")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--original-base-manifest", required=True, help="Approved exact ORIGINAL_072 decoded manifest; candidates are rejected")
    framework_args = parser.add_mutually_exclusive_group(required=True)
    framework_args.add_argument("--preserve-framework-manifest",
                        help="Exact Golden JAR inputs, bound to this production source commit")
    framework_args.add_argument("--terminal-framework-manifest", help="Exact current CI services DEX and preserved-member proof")
    args = parser.parse_args()

    root = pathlib.Path(args.root).resolve() if args.root else resolve_root()
    unpack = resolve_unpack(root, args.unpack)
    image_root = resolve_image_root(root, unpack)
    original_identity = require_original_base(unpack, args.original_base_manifest)
    payload = pathlib.Path(args.payload).resolve() if args.payload else root / "payload"
    if not payload.exists():
        raise SystemExit(f"Missing payload: {payload}")
    framework, framework_manifest_sha = (terminal_framework(root, args.terminal_framework_manifest)
        if args.terminal_framework_manifest else preserved_framework(root, args.preserve_framework_manifest))

    report = {
        "started_at": datetime.now().isoformat(timespec="seconds"),
        "root": str(root),
        "image_root": str(image_root),
        "unpack": str(unpack),
        "payload": str(payload),
        "dry_run": args.dry_run,
        "warnings": [],
        "build_base": "EXACT_ORIGINAL_072",
        "original_identity": original_identity,
    }
    apk = payload.joinpath(*APP_APK_PATH.split("/"))
    if not apk.exists():
        raise SystemExit(f"Missing {APP_APK_PATH}. Run scripts/build/BuildZuiControl.ps1 before applying the payload.")

    remove_reviewed_oem_preinstall(unpack, args.dry_run, report)
    remove_reviewed_oem_metadata(image_root, unpack, args.dry_run, report)
    entries = copy_payload(payload, unpack, args.dry_run, report)
    cleanup_retired_whitelists(unpack, args.dry_run, report)
    dumpsys_rel = "system_a/system/bin/dumpsys"
    entries.append((
        "ctx",
        "system_a",
        file_context_regex(dumpsys_rel),
        f"{file_context_regex(dumpsys_rel)} u:object_r:toolbox_exec:s0",
    ))
    patch_property_contexts(unpack, payload, args.dry_run, report)
    patch_service_contexts(unpack, payload, args.dry_run, report)
    patch_file_contexts(unpack, payload, args.dry_run, report)
    patch_plat_sepolicy(unpack, payload, args.dry_run, report)
    patch_vendor_sepolicy(unpack, payload, args.dry_run, report)
    for name, data in framework.items():
        destination = unpack / "system_a/system/framework" / name
        if not destination.is_file() or destination.is_symlink():
            raise SystemExit("ROUTE_A_PRODUCTION_HARD_FAIL: missing/linked target JAR.")
        if not args.dry_run:
            destination.write_bytes(data)
    report["framework_patch"] = "TERMINAL_EXACT_CI_SERVICES_EXTENSION" if args.terminal_framework_manifest else "PRESERVED_EXACT_GOLDEN_NO_SOURCE_CHANGE"
    report["framework_manifest_sha256"] = framework_manifest_sha
    report["framework_jars"] = {name: hashlib.sha256(data).hexdigest() for name, data in framework.items()}
    update_metadata(image_root, unpack, entries, args.dry_run, report)

    out_dir = image_root / "work" / "config"
    out_name = f"zui_control_payload_{stamp()}.json"
    if not args.dry_run:
        out_dir.mkdir(parents=True, exist_ok=True)
        write_text_lf(out_dir / out_name, json.dumps(report, ensure_ascii=False, indent=2))
        write_text_lf(out_dir / "zui_control_payload_latest.json", json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
