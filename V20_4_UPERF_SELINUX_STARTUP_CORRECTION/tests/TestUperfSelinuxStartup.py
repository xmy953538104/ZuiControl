#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest


REPO = Path(__file__).resolve().parents[2]
WRAPPER = REPO / "payload/system/bin/zui_uperf_service"
GATE = REPO / "payload/system/etc/zui_control/zui_uperf_crash_gate.sh"
INIT = REPO / "payload/system/etc/init/zui_scheduler.rc"
POLICY = REPO / "payload/patches/plat_sepolicy_zui_control.cil"
FILE_CONTEXTS = REPO / "payload/patches/plat_file_contexts_add.txt"
PROPERTY_CONTEXTS = REPO / "payload/patches/plat_property_contexts_add.txt"
VENDOR_POLICY = REPO / "payload/patches/vendor_sepolicy_zui_scheduler.cil"
PROOF = Path(__file__).with_name("android14_init_stop_contract.txt")

spec = importlib.util.spec_from_file_location(
    "verify_uperf_runtime_access", REPO / "scripts/VerifyUperfRuntimeAccess.py")
assert spec and spec.loader
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class UperfSelinuxStartupTests(unittest.TestCase):
    def test_01_runtime_access_graph_is_complete(self) -> None:
        report = verifier.verify(SimpleNamespace(
            mode="source",
            system_root=str(REPO / "payload/system"),
            file_contexts=str(FILE_CONTEXTS),
            property_contexts=str(PROPERTY_CONTEXTS),
            plat_policy=str(POLICY),
            vendor_policy=str(VENDOR_POLICY),
        ))
        self.assertTrue(report["ok"])
        self.assertGreaterEqual(report["graph_edges"], 22)
        self.assertEqual(report["removed_access"]["decision"], "REMOVE_ACCESS")

    def test_02_proc_uptime_has_narrow_decision(self) -> None:
        self.assertIn("< /proc/uptime", text(WRAPPER))
        self.assertIn("(allow performanced proc_uptime (file (getattr open read)))", text(POLICY))

    def test_03_no_broad_proc_file_allow(self) -> None:
        policy = text(POLICY)
        for target in ("proc", "proc_type", "fs_type"):
            self.assertNotIn(f"(allow performanced {target} (file ", policy)

    def test_04_scheduler_active_guard_removed(self) -> None:
        gate = text(GATE)
        self.assertNotIn("sys.zui_control.scheduler_active", gate)
        self.assertIn("sys.zui_control.scheduler_active", text(INIT))

    def test_05_no_broad_shell_property_allow(self) -> None:
        policy = text(POLICY)
        self.assertNotIn("(allow shell zui_control_scheduler_active_prop (file ", policy)
        for target in ("property_type", "system_property_type", "system_internal_property_type"):
            self.assertNotIn(f"(allow shell {target} (property_service (set)))", policy)

    def test_06_every_crash_gate_property_is_typed(self) -> None:
        gate = text(GATE)
        self.assertIn("FAIL_SAFE_PROP=sys.zui_control.uperf_fail_safe", gate)
        self.assertIn("sys.zui_control.uperf_fail_safe u:object_r:zui_control_uperf_fail_safe_prop:s0 exact bool",
                      text(PROPERTY_CONTEXTS))

    def test_07_every_wrapper_runtime_file_is_typed(self) -> None:
        contexts = text(FILE_CONTEXTS)
        self.assertIn("/system/bin/zui_uperf_service u:object_r:performanced_exec:s0", contexts)
        self.assertIn("/system/bin/uperf u:object_r:performanced_exec:s0", contexts)
        self.assertIn("/data/vendor/zui_control(/.*)? u:object_r:zui_control_data_file:s0", contexts)

    def test_08_fail_safe_set_permissions_exist(self) -> None:
        policy = text(POLICY)
        self.assertIn("(allow performanced zui_control_uperf_fail_safe_prop (property_service (set)))", policy)
        self.assertIn("(allow shell zui_control_uperf_fail_safe_prop (property_service (set)))", policy)

    def test_09_crash_counter_atomic_update_is_allowed(self) -> None:
        policy = text(POLICY)
        self.assertIn("(allow shell zui_control_data_file (dir (ioctl read write create getattr setattr lock rename open watch watch_reads add_name remove_name reparent search rmdir)))", policy)
        self.assertIn("(allow shell zui_control_data_file (file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))", policy)

    def test_10_fail_safe_stop_action_is_reachable(self) -> None:
        self.assertIn("on property:sys.zui_control.uperf_fail_safe=1\n    stop zui_uperf", text(INIT))

    def test_11_explicit_stop_contract_is_pinned(self) -> None:
        proof = text(PROOF)
        self.assertIn("Service::Stop=StopOrReset(SVC_DISABLED)", proof)
        self.assertIn("before_onrestart", proof)
        self.assertIn("86.36_seconds_stopped_without_onrestart", proof)

    def test_12_no_periodic_wrapper_loop(self) -> None:
        wrapper = text(WRAPPER)
        for token in ("sleep ", "pidof uperf", "killall", "uperf_process_count", "grep "):
            self.assertNotIn(token, wrapper)

    def test_13_fifo_design_is_unchanged(self) -> None:
        wrapper = text(WRAPPER)
        self.assertIn('mkfifo "$LOG_PIPE"', wrapper)
        self.assertIn('while IFS= read -r line <&8; do', wrapper)

    def test_14_top_resumed_design_is_unchanged(self) -> None:
        self.assertEqual(
            sha256(REPO / "framework_patch/src/services/com/zui/server/control/ZuiControlService.java"),
            "b4903fffb41dfd21701c8e7fb694f32f6565c1cdc1e5f518a1f56a934149adea")
        self.assertEqual(
            sha256(REPO / "framework_patch/src/services/com/zui/server/control/ZuiControlHooks.java"),
            "189973997fdb80a483ae631a5404e867e357239c5226091fa6b8ad964a0ed5d1")

    def test_15_tuning_and_binary_are_unchanged(self) -> None:
        self.assertEqual(sha256(REPO / "payload/system/etc/zui_control/uperf-sm8650.json"),
                         "b8bece967e0c8e3e7e2acf31c4772a1e29e4aa2ee91da7af1a7f220faa4b0ff9")
        self.assertEqual(sha256(REPO / "payload/system/bin/uperf"),
                         "f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8")

    def test_16_refresh_and_asoul_are_unchanged(self) -> None:
        self.assertEqual(sha256(REPO / "payload/system/bin/AsoulOpt"),
                         "7a2ee5d67ba7c057066176334eca9256e376427916429d66b7593cbb5538ec86")
        self.assertEqual(sha256(REPO / "payload/system/etc/init/zui_refresh_kill_switch.rc"),
                         "0161a9980777b4313d0a5935b0e861e1afea86c5b4eb07ef5b88e44f20143c62")


if __name__ == "__main__":
    unittest.main(verbosity=2)
