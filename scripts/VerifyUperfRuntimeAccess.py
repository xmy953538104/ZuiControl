#!/usr/bin/env python3
"""Verify the Uperf runtime access graph against source or final artifacts.

This is deliberately an allowlist verifier, not an audit2allow wrapper.  Each
required rule below maps to one concrete edge in the reviewed runtime graph.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys


def read(path: Path) -> str:
    if not path.is_file():
        raise ValueError(f"missing input: {path}")
    return path.read_text(encoding="utf-8", errors="strict").replace("\r\n", "\n")


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise ValueError(f"{label}: missing {needle}")


def forbid(text: str, needle: str, label: str) -> None:
    if needle in text:
        raise ValueError(f"{label}: forbidden {needle}")


def edge(component: str, domain: str, operation: str, resource: str,
         resolved_type: str, object_class: str, permissions: str,
         policy_rule: str, necessity: str) -> dict[str, str]:
    return {
        "component": component,
        "domain": domain,
        "operation": operation,
        "resource": resource,
        "resolved_type": resolved_type,
        "class": object_class,
        "permissions": permissions,
        "policy_rule": policy_rule,
        "necessity": necessity,
        "decision": "ALLOW_NARROW_POLICY",
    }


CUSTOM_RULES = [
    edge("wrapper", "performanced", "execute without transition", "/system/bin/uperf",
         "performanced_exec", "file", "read getattr map execute open execute_no_trans",
         "(allow performanced performanced_exec (file (read getattr map execute open execute_no_trans entrypoint)))",
         "launch the pinned Uperf worker in the supervised service domain"),
    edge("wrapper", "performanced", "execute shell utilities", "/system/bin/toybox applets",
         "toolbox_exec", "file", "read getattr map execute open execute_no_trans",
         "(allow performanced toolbox_exec (file (read getattr map execute open execute_no_trans)))",
         "rm, mkfifo, chmod and mv used by the fixed wrapper"),
    edge("wrapper", "performanced", "execute script interpreter", "/system/bin/sh",
         "shell_exec", "file", "read getattr map execute open execute_no_trans",
         "(allow performanced shell_exec (file (read getattr map execute open execute_no_trans)))",
         "run the fixed wrapper and its shell operations without a domain transition"),
    edge("wrapper", "performanced", "read monotonic elapsed time", "/proc/uptime",
         "proc_uptime", "file", "getattr open read",
         "(allow performanced proc_uptime (file (getattr open read)))",
         "startup deadline and 20-second worker-crash window require monotonic elapsed time"),
    edge("wrapper/worker", "performanced", "traverse/create runtime paths",
         "/data/vendor/zui_control/**", "zui_control_data_file", "dir",
         "getattr open read search write add_name remove_name create setattr",
         "(allow performanced zui_control_data_file (dir (getattr open read search write add_name remove_name create setattr)))",
         "private FIFO, log, readiness marker and crash-state parent directories"),
    edge("wrapper/worker", "performanced", "read/write/create runtime files",
         "/data/vendor/zui_control/**", "zui_control_data_file", "file",
         "getattr open read write create append map watch watch_reads setattr unlink",
         "(allow performanced zui_control_data_file (file (getattr open read write create append map watch watch_reads setattr unlink)))",
         "config, effective mode, log, marker, temporary and counter files"),
    edge("wrapper/worker", "performanced", "read runtime symlinks",
         "/data/vendor/zui_control/**", "zui_control_data_file", "lnk_file", "getattr read",
         "(allow performanced zui_control_data_file (lnk_file (getattr read)))",
         "canonical A-SOUL and scheduler runtime symlinks"),
    edge("wrapper", "performanced", "create/open/read/write/unlink FIFO",
         "/data/vendor/zui_control/uperf/.service_log_pipe", "zui_control_data_file",
         "fifo_file", "getattr open read write create setattr unlink",
         "(allow performanced zui_control_data_file (fifo_file (getattr open read write create setattr unlink)))",
         "event-driven worker log and EOF supervision"),
    edge("wrapper/worker", "performanced", "place process in background cpuset",
         "/dev/cpuset/background/tasks", "cgroup", "file",
         "ioctl read write create getattr setattr lock append map open unlink",
         "(allow performanced cgroup (file (ioctl read write create getattr setattr lock append map open unlink)))",
         "existing service and worker cgroup placement"),
    edge("wrapper/worker", "performanced", "traverse background cpuset",
         "/dev/cpuset/background", "cgroup", "dir",
         "ioctl read write create getattr setattr lock open add_name remove_name search rmdir",
         "(allow performanced cgroup (dir (ioctl read write create getattr setattr lock open add_name remove_name search rmdir)))",
         "reach the cgroup tasks file"),
    edge("wrapper", "performanced", "read fail-safe property",
         "sys.zui_control.uperf_fail_safe", "zui_control_uperf_fail_safe_prop", "file",
         "getattr map open read",
         "(allow performanced zui_control_uperf_fail_safe_prop (file (getattr map open read)))",
         "property client lookup before a set"),
    edge("wrapper", "performanced", "set fail-safe property",
         "sys.zui_control.uperf_fail_safe", "zui_control_uperf_fail_safe_prop",
         "property_service", "set",
         "(allow performanced zui_control_uperf_fail_safe_prop (property_service (set)))",
         "three worker deaths in 20 seconds must stop the service"),
    edge("crash-gate", "shell", "traverse/update crash state",
         "/data/vendor/zui_control/uperf", "zui_control_data_file", "dir",
         "ioctl read write create getattr setattr lock rename open watch watch_reads add_name remove_name reparent search rmdir",
         "(allow shell zui_control_data_file (dir (ioctl read write create getattr setattr lock rename open watch watch_reads add_name remove_name reparent search rmdir)))",
         "readiness marker and atomic rapid-crash counter replacement"),
    edge("crash-gate", "shell", "read/write/rename/unlink crash state",
         "/data/vendor/zui_control/uperf/.service_*", "zui_control_data_file", "file",
         "ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads",
         "(allow shell zui_control_data_file (file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))",
         "readiness marker, temporary counter and atomic counter state"),
    edge("crash-gate", "shell", "read fail-safe property",
         "sys.zui_control.uperf_fail_safe", "zui_control_uperf_fail_safe_prop", "file",
         "getattr map open read",
         "(allow shell zui_control_uperf_fail_safe_prop (file (getattr map open read)))",
         "property client lookup before a set"),
    edge("crash-gate", "shell", "set fail-safe property",
         "sys.zui_control.uperf_fail_safe", "zui_control_uperf_fail_safe_prop",
         "property_service", "set",
         "(allow shell zui_control_uperf_fail_safe_prop (property_service (set)))",
         "three consecutive rapid whole-service deaths must stop the service"),
    edge("init", "init", "set fail-safe property",
         "sys.zui_control.uperf_fail_safe", "zui_control_uperf_fail_safe_prop",
         "property_service", "set",
         "(allow init zui_control_uperf_fail_safe_prop (property_service (set)))",
         "boot/start/explicit-stop reset and property-triggered service stop"),
    edge("init", "init", "set scheduler ownership property",
         "sys.zui_control.scheduler_active", "zui_control_scheduler_active_prop",
         "property_service", "set",
         "(allow init zui_control_scheduler_active_prop (property_service (set)))",
         "init-native OEM fence ownership; not read by the crash gate"),
    edge("init", "init", "prepare/relabel runtime directory",
         "/data/vendor/zui_control/**", "zui_control_data_file", "dir",
         "getattr open read search write add_name remove_name create setattr relabelto",
         "(allow init zui_control_data_file (dir (getattr open read search write add_name remove_name create setattr relabelto)))",
         "post-fs-data directory creation, cleanup and restorecon"),
    edge("init", "init", "prepare/relabel runtime files",
         "/data/vendor/zui_control/**", "zui_control_data_file", "file",
         "getattr open read write create unlink setattr relabelfrom relabelto",
         "(allow init zui_control_data_file (file (getattr open read write create unlink setattr relabelfrom relabelto)))",
         "post-fs-data cleanup, mode file creation and restorecon"),
    edge("wrapper/worker", "performanced", "own supervised processes", "self",
         "performanced", "capability", "chown dac_override fowner kill",
         "(allow performanced self (capability (chown dac_override fowner kill)))",
         "pinned worker lifecycle and runtime file ownership"),
    edge("wrapper/worker", "performanced", "signal supervised processes", "self",
         "performanced", "process", "signal sigkill signull",
         "(allow performanced self (process (signal sigkill signull)))",
         "worker lifecycle inside the init service cgroup"),
    edge("system_server scene publisher", "system_server", "call Uperf worker binder",
         "performanced", "performanced", "binder", "call",
         "(allow system_server performanced (binder (call)))",
         "event-driven top-resumed scene publication"),
    edge("system_server scene publisher", "system_server", "write Uperf request FIFO",
         "performanced", "performanced", "fifo_file", "write",
         "(allow system_server performanced (fifo_file (write)))",
         "event-driven Uperf request transport"),
    edge("worker", "performanced", "find Activity service", "activity service",
         "activity_service", "service_manager", "find",
         "(allow performanced activity_service (service_manager (find)))",
         "pinned Uperf worker foreground/process integration"),
    edge("worker", "performanced", "inspect and schedule app processes", "/proc/<app>",
         "appdomain", "process", "getsched setsched signull",
         "(allow performanced appdomain (process (getsched setsched signull)))",
         "Uperf process scheduling core"),
    edge("worker", "performanced", "traverse app process directories", "/proc/<app>",
         "appdomain", "dir", "getattr open read search",
         "(allow performanced appdomain (dir (getattr open read search)))",
         "locate process scheduling targets"),
    edge("worker", "performanced", "read app process metadata", "/proc/<app>/**",
         "appdomain", "file", "getattr open read",
         "(allow performanced appdomain (file (getattr open read)))",
         "identify and classify scheduling targets"),
    edge("worker", "performanced", "enumerate proc root", "/proc", "proc", "dir",
         "getattr open read search",
         "(allow performanced proc (dir (getattr open read search)))",
         "enumerate PIDs; no broad proc file read is granted"),
    edge("worker", "performanced", "read aggregate scheduler statistics", "/proc/stat",
         "proc_stat", "file", "getattr open read",
         "(allow performanced proc_stat (file (getattr open read)))",
         "approved Uperf load classification"),
    edge("worker", "performanced", "traverse generic sysfs", "/sys/**", "sysfs", "dir",
         "getattr open read search",
         "(allow performanced sysfs (dir (getattr open read search)))",
         "reach specifically typed CPU controls"),
    edge("worker", "performanced", "read CPU controls", "/sys/devices/system/cpu/**",
         "sysfs_devices_system_cpu", "file", "getattr open read write append setattr",
         "(allow performanced sysfs_devices_system_cpu (file (getattr open read write append setattr)))",
         "approved v1.0.6 CPU tuning"),
    edge("worker", "performanced", "traverse CPU controls", "/sys/devices/system/cpu/**",
         "sysfs_devices_system_cpu", "dir", "getattr open read search",
         "(allow performanced sysfs_devices_system_cpu (dir (getattr open read search)))",
         "reach approved CPU tuning files"),
    edge("worker", "performanced", "read/write WALT scheduler knobs", "/proc/sys/walt/**",
         "zui_scheduler_proc", "file", "getattr open read write append setattr",
         "(allow performanced zui_scheduler_proc (file (getattr open read write append setattr)))",
         "approved v1.0.6 scheduler tuning"),
    edge("worker", "performanced", "traverse WALT scheduler knobs", "/proc/sys/walt/**",
         "zui_scheduler_proc", "dir", "getattr open read search",
         "(allow performanced zui_scheduler_proc (dir (getattr open read search)))",
         "reach specifically labeled WALT knob files"),
    edge("worker", "performanced", "read cgroup-v2 state", "/sys/fs/cgroup/**",
         "cgroup_v2", "file", "getattr open read",
         "(allow performanced cgroup_v2 (file (getattr open read)))",
         "approved Uperf scheduling state inspection"),
    edge("worker", "performanced", "read input devices", "/dev/input/**", "input_device",
         "chr_file", "ioctl read getattr lock map open",
         "(allow performanced input_device (chr_file (ioctl read getattr lock map open)))",
         "Uperf input-driven boost behavior"),
    edge("worker", "performanced", "traverse input devices", "/dev/input", "input_device",
         "dir", "ioctl read getattr lock open watch watch_reads search",
         "(allow performanced input_device (dir (ioctl read getattr lock open watch watch_reads search)))",
         "reach input event nodes"),
]


FINAL_ONLY_RULES = [
    edge("crash-gate", "shell", "read monotonic elapsed time", "/proc/uptime",
         "proc_uptime", "file", "ioctl read getattr lock map open watch watch_reads",
         "(allow shell proc_uptime (file (ioctl read getattr lock map open watch watch_reads)))",
         "classify whole-service lifetime without wall-clock jumps"),
    edge("init", "init", "execute service with domain transition",
         "/system/bin/zui_uperf_service", "performanced_exec", "process", "type transition",
         "(typetransition init performanced_exec process performanced)",
         "start the supervised wrapper in performanced rather than init"),
    edge("init", "init", "open service executable", "/system/bin/zui_uperf_service",
         "performanced_exec", "file", "read getattr map execute open",
         "(allow init performanced_exec (file (read getattr map execute open)))",
         "execute the service entrypoint before domain transition"),
    edge("crash-gate", "shell", "execute shell utilities", "/system/bin/toybox applets",
         "toolbox_exec", "file", "ioctl read getattr lock map execute open watch watch_reads execute_no_trans",
         "(allow shell toolbox_exec (file (ioctl read getattr lock map execute open watch watch_reads execute_no_trans)))",
         "chmod, mv and rm used by the bounded crash gate"),
]


CONTEXTS = [
    ("/system/bin/uperf", "performanced_exec"),
    ("/system/bin/zui_uperf_service", "performanced_exec"),
    ("/system/bin/AsoulOpt", "performanced_exec"),
    ("/data/vendor/zui_control(/.*)?", "zui_control_data_file"),
]


def verify(args: argparse.Namespace) -> dict[str, object]:
    system = Path(args.system_root)
    wrapper = read(system / "bin/zui_uperf_service")
    crash_gate = read(system / "etc/zui_control/zui_uperf_crash_gate.sh")
    init_rc = read(system / "etc/init/zui_scheduler.rc")
    config = json.loads(read(system / "etc/zui_control/uperf-sm8650.json"))
    uperf_binary = (system / "bin/uperf").read_bytes()
    file_contexts = read(Path(args.file_contexts))
    property_contexts = read(Path(args.property_contexts))
    plat_policy = read(Path(args.plat_policy))
    vendor_policy = read(Path(args.vendor_policy)) if args.vendor_policy else ""

    require(wrapper, "< /proc/uptime", "wrapper monotonic time source")
    require(wrapper, 'mkfifo "$LOG_PIPE"', "FIFO architecture")
    require(wrapper, 'while IFS= read -r line <&8; do', "event-driven steady state")
    for token in ("sleep ", "pidof uperf", "killall", "uperf_process_count", "grep "):
        forbid(wrapper, token, "periodic wrapper regression")
    require(crash_gate, "< /proc/uptime", "crash-gate monotonic time source")
    require(crash_gate, '[ "$count" -lt 3 ] || setprop "$FAIL_SAFE_PROP" 1',
            "whole-service fail-safe set")
    forbid(crash_gate, "sys.zui_control.scheduler_active", "retired crash-gate guard")
    for token in ("while ", "sleep "):
        forbid(crash_gate, token, "crash-gate timer regression")
    require(init_rc, "on property:sys.zui_control.uperf_fail_safe=1\n    stop zui_uperf",
            "fail-safe stop reachability")
    require(init_rc, "onrestart exec u:r:shell:s0 root shell -- /system/bin/sh /system/etc/zui_control/zui_uperf_crash_gate.sh",
            "whole-service crash gate")

    modules = config["modules"]
    if modules["sfanalysis"]["enable"] is not False:
        raise ValueError("sfanalysis must remain disabled after the confirmed SurfaceFlinger access failure")
    if modules["input"]["enable"] is not True:
        raise ValueError("input module activation changed unexpectedly")
    if modules["sysfs"]["enable"] is not True:
        raise ValueError("sysfs module activation changed unexpectedly")
    if modules["sched"]["enable"] is not False:
        raise ValueError("sched module must remain disabled; A-SOUL owns thread placement")
    if modules["switcher"]["switchInode"] != "/data/vendor/zui_control/uperf/effective_powermode.txt":
        raise ValueError("switcher effective-mode path changed unexpectedly")
    for marker in (b"SfAnalysisListener", b"/system/bin/surfaceflinger", b"sfanalysis"):
        if marker not in uperf_binary:
            raise ValueError(f"pinned Uperf binary lacks SFAnalysis audit marker: {marker!r}")

    module_access_review = [
        {
            "module": "sfanalysis",
            "activated": False,
            "decision": "DISABLE_UNNEEDED_MODULE",
            "known_resources": [
                "/system/bin/surfaceflinger",
                "/system/lib64/libandroidfw.so",
                "/system/lib64/libandroid.so",
            ],
            "runtime_evidence": "enabled=true caused blocking surfaceflinger_exec:file read AVCs in both startup workers",
            "coverage": "PARTIAL_STATIC_CLOSED_SOURCE",
        },
        {
            "module": "input",
            "activated": True,
            "decision": "KEEP_WITH_NARROW_EXISTING_POLICY",
            "known_resources": ["/dev/input/**"],
            "runtime_evidence": "current corrected candidate device proof pending",
            "coverage": "PARTIAL_STATIC_CLOSED_SOURCE",
        },
        {
            "module": "switcher",
            "activated": True,
            "decision": "KEEP_CANONICAL_EVENT_DRIVEN_MODE_PATHS",
            "known_resources": [
                modules["switcher"]["switchInode"],
                modules["switcher"]["perapp"],
            ],
            "runtime_evidence": "current corrected candidate device proof pending",
            "coverage": "PARTIAL_STATIC_CLOSED_SOURCE",
        },
        {
            "module": "sysfs",
            "activated": True,
            "decision": "KEEP_WITH_TYPED_CPU_WALT_MSM_POLICY",
            "known_resources": sorted(set(modules["sysfs"]["knob"].values())),
            "runtime_evidence": "current corrected candidate device proof pending",
            "coverage": "PARTIAL_STATIC_CLOSED_SOURCE",
        },
        {
            "module": "sched",
            "activated": False,
            "decision": "KEEP_DISABLED_ASOUL_OWNS_THREAD_PLACEMENT",
            "known_resources": ["/proc/<pid>/task/<tid>/**"],
            "runtime_evidence": "disabled by production architecture",
            "coverage": "PARTIAL_STATIC_CLOSED_SOURCE",
        },
    ]

    for path, expected_type in CONTEXTS:
        pattern = re.compile(rf"(?m)^{re.escape(path)}\s+(?:--\s+)?u:object_r:{expected_type}:s0$")
        if not pattern.search(file_contexts):
            raise ValueError(f"file context missing: {path} -> {expected_type}")
    require(property_contexts,
            "sys.zui_control.uperf_fail_safe u:object_r:zui_control_uperf_fail_safe_prop:s0 exact bool",
            "typed fail-safe property")
    require(property_contexts,
            "sys.zui_control.scheduler_active u:object_r:zui_control_scheduler_active_prop:s0 exact enum 0 1",
            "typed scheduler property")

    graph = list(CUSTOM_RULES)
    if args.mode == "final":
        graph += FINAL_ONLY_RULES
        require(file_contexts, "/system(/.*)?", "final system_file fallback")
    for item in graph:
        if item["policy_rule"].startswith("(allow performanced_34_0"):
            require(vendor_policy, item["policy_rule"], item["component"])
        else:
            require(plat_policy, item["policy_rule"], item["component"])

    if vendor_policy:
        vendor_rule = "(allow performanced_34_0 vendor_sysfs_msm_perf (file (ioctl read write getattr setattr lock append map open)))"
        require(vendor_policy, vendor_rule, "worker msm_performance access")
        graph.append(edge("worker", "performanced", "read/write msm_performance knobs",
                          "/sys/module/msm_performance/**", "vendor_sysfs_msm_perf", "file",
                          "ioctl read write getattr setattr lock append map open", vendor_rule,
                          "approved v1.0.6 vendor performance tuning"))

    broad_patterns = {
        "performanced broad proc file": r"\(allow\s+performanced\s+(?:proc|proc_type|fs_type)\s+\(file\s+",
        "performanced SurfaceFlinger access": r"\(allow\s+performanced(?:_34_0)?\s+[^\s()]*surfaceflinger[^\s()]*\s+\(",
        "shell scheduler property read": r"\(allow\s+shell\s+zui_control_scheduler_active_prop\s+\(file\s+",
        "shell broad property write": r"\(allow\s+shell\s+(?:property_type|system_property_type|system_internal_property_type)\s+\(property_service\s+\(set\)\)\)",
        "performanced permissive": r"\(permissive\s+performanced\)",
        "shell permissive": r"\(permissive\s+shell\)",
        "neverallow bypass marker": r"disable[_-]?neverallow|neverallow[_-]?bypass",
    }
    combined = plat_policy + "\n" + vendor_policy
    for label, pattern in broad_patterns.items():
        if re.search(pattern, combined, re.IGNORECASE):
            raise ValueError(f"forbidden broad policy: {label}")

    return {
        "ok": True,
        "mode": args.mode,
        "graph_edges": len(graph),
        "graph": graph,
        "access_graph_completeness": {
            "wrapper_init_explicit_code": "COMPLETE_STATIC_REVIEW",
            "closed_source_config_modules": "PARTIAL_STATIC_REVIEW",
            "device_runtime_for_corrected_candidate": "NOT_YET_PROVEN",
        },
        "config_activated_module_access_review": module_access_review,
        "removed_access": {
            "component": "crash-gate",
            "domain": "shell",
            "resource": "sys.zui_control.scheduler_active",
            "decision": "REMOVE_ACCESS",
            "reason": "Android 14 explicit stop does not execute onrestart; target stop observation confirmed it",
        },
        "broad_policy_rejected": list(broad_patterns),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("source", "final"), required=True)
    parser.add_argument("--system-root", required=True)
    parser.add_argument("--file-contexts", required=True)
    parser.add_argument("--property-contexts", required=True)
    parser.add_argument("--plat-policy", required=True)
    parser.add_argument("--vendor-policy")
    parser.add_argument("--report")
    args = parser.parse_args()
    try:
        report = verify(args)
        serialized = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
        if args.report:
            Path(args.report).write_text(serialized, encoding="utf-8")
        print(serialized, end="")
        print(f"{args.mode.upper()}_SERVICE_ACCESS_GRAPH_VERIFY=PASS")
        return 0
    except (OSError, ValueError) as exc:
        print(f"SERVICE_ACCESS_GRAPH_VERIFY=FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
