#!/usr/bin/env python3
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
from datetime import datetime


APP_PACKAGE = "com.zui.zuicontrol"
LEGACY_APP_PACKAGE = "com.zui.zuiperfctl"

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def stamp():
    return datetime.now().strftime("%Y%m%d_%H%M%S")


def resolve_root():
    return pathlib.Path(__file__).resolve().parents[1]


def resolve_unpack(root, unpack_arg):
    candidates = []
    if unpack_arg:
        candidates.append(pathlib.Path(unpack_arg))
    candidates.append(root / "work" / "unpack")
    candidates.append(root.parent / "work" / "unpack")
    for candidate in candidates:
        candidate = candidate.expanduser().resolve()
        if any((candidate / name).exists() for name in ["system_a", "vendor_a", "product_a", "odm_a"]):
            return candidate
    raise SystemExit("Cannot find unpack root. Expected work/unpack with system_a.")


def resolve_image_root(root, unpack):
    unpack = pathlib.Path(unpack).resolve()
    if unpack.name == "unpack" and unpack.parent.name == "work":
        return unpack.parent.parent
    return root


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
    if rel in ["system_a/system/bin/zui_controld", "system_a/system/bin/AsoulOpt"]:
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
        path.write_text("\n".join(kept) + "\n", encoding="utf-8")
    return removed


def cleanup_legacy_payload(unpack, dry_run, report):
    removed = []
    legacy = [
        "system_a/system/priv-app/ZuiperfCtl",
        "system_a/system/bin/zui_perfctld",
        "system_a/system/etc/init/zui_perfctld.rc",
        "system_a/system/etc/zui_perfctl",
        "system_a/system/etc/permissions/privapp-permissions-zui-perfctl.xml",
        "system_a/system/etc/default-permissions/default-permissions-zui-zuiperfctl.xml",
    ]
    for rel in legacy:
        remove_path(unpack / rel, dry_run, removed)
    report["legacy_removed"] = removed


def cleanup_legacy_metadata(image_root, unpack, dry_run, report):
    needles = [
        "zui_perfctl",
        "zui_perfctld",
        "ZuiperfCtl",
        "zuiperfctl",
        "zui-perfctl",
    ]
    targets = [
        unpack / "config" / "system_a_fs_config",
        unpack / "config" / "system_a_file_contexts",
        image_root / "work" / "config" / "erofs_overrides" / "system_a_fs_config",
        image_root / "work" / "config" / "erofs_overrides" / "system_a_file_contexts",
        unpack / "system_a" / "system" / "etc" / "selinux" / "plat_property_contexts",
        unpack / "system_a" / "system" / "etc" / "selinux" / "plat_service_contexts",
        unpack / "system_a" / "system" / "etc" / "selinux" / "plat_sepolicy.cil",
        unpack / "vendor_a" / "etc" / "selinux" / "vendor_sepolicy.cil",
    ]
    removed = {}
    for target in targets:
        lines = remove_lines_containing(target, needles, dry_run)
        if lines:
            removed[str(target)] = lines
    report["legacy_metadata_removed"] = removed


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


def read_text_lossy(path):
    return path.read_text(encoding="utf-8", errors="ignore")


def write_text_if_changed(path, original, updated, dry_run, changed):
    if updated == original:
        return
    changed.append(str(path))
    if not dry_run:
        path.write_text(updated, encoding="utf-8")


def remove_legacy_package_lines(text):
    return "\n".join(
        line for line in text.splitlines()
        if LEGACY_APP_PACKAGE not in line
    ) + "\n"


def ensure_lines_before_marker(text, marker, lines):
    additions = [line for line in lines if line not in text]
    if not additions:
        return text, True
    block = "\n".join(additions) + "\n"
    index = text.find(marker)
    if index < 0:
        return text.rstrip() + "\n" + block, False
    return text[:index] + block + text[index:], True


def ensure_line_in_section(text, section_name, line):
    open_tag = f"<{section_name}>"
    close_tag = f"</{section_name}>"
    section_start = text.find(open_tag)
    section_end = text.find(close_tag, section_start)
    if section_start < 0 or section_end < 0:
        return text, False
    section = text[section_start:section_end]
    if line in section:
        return text, True
    marker = "</WhiteListApp>"
    marker_index = text.find(marker, section_start, section_end)
    if marker_index < 0:
        return text, False
    return text[:marker_index] + line + "\n" + text[marker_index:], True


def patch_text_file(path, dry_run, changed, warnings, transform):
    path = pathlib.Path(path)
    if not path.exists():
        warnings.append(f"missing keepalive config: {path}")
        return
    original = read_text_lossy(path)
    updated, ok = transform(original)
    if not ok:
        warnings.append(f"keepalive config marker not found: {path}")
    write_text_if_changed(path, original, updated, dry_run, changed)


def patch_keepalive_configs(unpack, dry_run, report):
    base = unpack / "system_a" / "system" / "etc"
    changed = []
    warnings = []

    def patch_mem_cleaner(text):
        text = remove_legacy_package_lines(text)
        ok_all = True
        text, ok = ensure_lines_before_marker(
            text,
            "    </PermanentWhiteList>",
            [f"        <PermanentPackageName>{APP_PACKAGE}</PermanentPackageName>"],
        )
        ok_all = ok_all and ok
        text, ok = ensure_lines_before_marker(
            text,
            "</ZuiMemCleanerConfig>",
            [f"    <Whitelist package=\"{APP_PACKAGE}\" />"],
        )
        return text, ok_all and ok

    def patch_power_policy(text):
        text = remove_legacy_package_lines(text)
        return ensure_lines_before_marker(
            text,
            "        </WhitelistApp>",
            [f"            <Item>{APP_PACKAGE}</Item>"],
        )

    def patch_bgintents(text):
        text = remove_legacy_package_lines(text)
        return ensure_lines_before_marker(
            text,
            "</config>",
            [
                f"        <default_allow_auto_run package=\"{APP_PACKAGE}\" allowtype=\"hide\" />",
                f"        <hide-fg-notify package=\"{APP_PACKAGE}\" />",
                f"        <hide-alertwindow-notify package=\"{APP_PACKAGE}\" />",
                f"        <allow-bg-intents package=\"{APP_PACKAGE}\" />",
            ],
        )

    def patch_zuipp_power(text):
        text = remove_legacy_package_lines(text)
        ok_all = True
        text, ok = ensure_line_in_section(text, "OverHeat", f"            <Item>{APP_PACKAGE}</Item>")
        ok_all = ok_all and ok
        text, ok = ensure_line_in_section(text, "DeviceIdle", f"            <Item>{APP_PACKAGE}</Item>")
        return text, ok_all and ok

    patch_text_file(base / "ZuiMemCleanerConfig.xml", dry_run, changed, warnings, patch_mem_cleaner)
    patch_text_file(base / "ZuiPowerPolicyConfig.xml", dry_run, changed, warnings, patch_power_policy)
    patch_text_file(
        base / "motorola" / "bgintents" / "com.zui.safecenter.autorun.xml",
        dry_run,
        changed,
        warnings,
        patch_bgintents,
    )
    patch_text_file(base / "zuipp_powercfg.xml", dry_run, changed, warnings, patch_zuipp_power)

    report["keepalive_configs_changed"] = changed
    report["keepalive_config_warnings"] = warnings


def patch_legacy_text_markers(unpack, dry_run, report):
    targets = [
        unpack / "system_a" / "system" / "etc" / "game_policy.xml",
        unpack / "system_a" / "system" / "etc" / "performanceconfig.xml",
    ]
    changed = []
    for path in targets:
        if not path.exists():
            continue
        original = read_text_lossy(path)
        updated = original.replace("ZuiperfCtl", "ZuiControl")
        write_text_if_changed(path, original, updated, dry_run, changed)
    report["legacy_text_markers_changed"] = changed


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
        target.write_text("\n".join(current + additions) + "\n", encoding="utf-8")
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
        target.write_text("\n".join(updated) + "\n", encoding="utf-8")
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
    additions, changed = merge_unique_lines_by_key(target, read_patch_lines(patch), dry_run)
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
    ]
    removed = remove_lines_containing(target, obsolete, dry_run)
    patch_lines = read_patch_lines(patch)
    additions = append_unique_lines(target, patch_lines, dry_run)
    report["plat_sepolicy_removed"] = removed
    report["plat_sepolicy_added"] = additions
    if (patch_lines or removed) and not dry_run:
        update_plat_mapping_hash(unpack, report)


def patch_vendor_sepolicy(unpack, payload, dry_run, report):
    patch = payload / "patches" / "vendor_sepolicy_zui_control_kgsl.cil"
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


def patch_framework_jars(root, unpack, dry_run, report):
    script = root / "scripts" / "PatchZuiControlFramework.py"
    if not script.exists():
        report["warnings"].append(f"missing framework patch script: {script}")
        return
    cmd = [sys.executable, str(script), "--unpack", str(unpack)]
    if dry_run:
        cmd.append("--dry-run")
    subprocess.run(cmd, check=True)
    report["framework_patch"] = "applied"


def main():
    parser = argparse.ArgumentParser(description="Apply ZuiControl payload into an unpacked image tree.")
    parser.add_argument("--root", help="Project root containing work/config, default: this repository root")
    parser.add_argument("--unpack", help="Unpacked image root, default: work/unpack")
    parser.add_argument("--payload", help="Payload root, default: payload")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    root = pathlib.Path(args.root).resolve() if args.root else resolve_root()
    unpack = resolve_unpack(root, args.unpack)
    image_root = resolve_image_root(root, unpack)
    payload = pathlib.Path(args.payload).resolve() if args.payload else root / "payload"
    if not payload.exists():
        raise SystemExit(f"Missing payload: {payload}")

    report = {
        "started_at": datetime.now().isoformat(timespec="seconds"),
        "root": str(root),
        "image_root": str(image_root),
        "unpack": str(unpack),
        "payload": str(payload),
        "dry_run": args.dry_run,
        "warnings": [],
    }
    report["zuipp_xml_baseline_model"] = {
        "baked_baseline": "/system/etc/game_policy.xml and /system/etc/performanceconfig.xml as shipped in the built image; they may already be ZuiControl custom tables.",
        "active_runtime": "/data/vendor/zui_control/zuipp/active/ is bind-mounted to /system/etc at boot and after each XML apply.",
        "payload_templates": "/system/etc/zui_control/default_game_policy.xml and default_performanceconfig.xml are templates/fallback inputs, not automatically treated as official originals.",
        "official_original": "Only expose an official-original restore path when an original pair was explicitly captured under /data/vendor/zui_control/zuipp/official_original/.",
    }

    apk = payload / "system" / "priv-app" / "ZuiControl" / "ZuiControl.apk"
    if not apk.exists():
        report["warnings"].append("ZuiControl.apk is missing. Run scripts/BuildZuiControl.ps1 before packing a bootable image with the app.")

    cleanup_legacy_payload(unpack, args.dry_run, report)
    cleanup_legacy_metadata(image_root, unpack, args.dry_run, report)
    entries = copy_payload(payload, unpack, args.dry_run, report)
    patch_property_contexts(unpack, payload, args.dry_run, report)
    patch_service_contexts(unpack, payload, args.dry_run, report)
    patch_file_contexts(unpack, payload, args.dry_run, report)
    patch_plat_sepolicy(unpack, payload, args.dry_run, report)
    patch_vendor_sepolicy(unpack, payload, args.dry_run, report)
    patch_framework_jars(root, unpack, args.dry_run, report)
    patch_keepalive_configs(unpack, args.dry_run, report)
    patch_legacy_text_markers(unpack, args.dry_run, report)
    update_metadata(image_root, unpack, entries, args.dry_run, report)

    out_dir = image_root / "work" / "config"
    out_name = f"zui_control_payload_{stamp()}.json"
    if not args.dry_run:
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / out_name).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        (out_dir / "zui_control_payload_latest.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
