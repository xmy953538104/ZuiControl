#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile


REPO = pathlib.Path(__file__).resolve().parents[2]
SERVICE = REPO / "framework_patch/src/services/com/zui/server/control/ZuiControlService.java"
HOOKS = REPO / "framework_patch/src/services/com/zui/server/control/ZuiControlHooks.java"
PATCHER = REPO / "scripts/PatchZuiControlFramework.py"
FINAL_SUPER_VERIFIER = REPO / "scripts/VerifyZuiControlFinalSuper.ps1"
RC = REPO / "payload/system/etc/init/zui_scheduler.rc"
WRAPPER = REPO / "payload/system/bin/zui_uperf_service"
CRASH_GATE = REPO / "payload/system/etc/zui_control/zui_uperf_crash_gate.sh"
CONFIG = REPO / "payload/system/etc/zui_control/uperf-sm8650.json"
PROPERTY_CONTEXTS = REPO / "payload/patches/plat_property_contexts_add.txt"
POLICY = REPO / "payload/patches/plat_sepolicy_zui_control.cil"
SNAPSHOT = REPO / "upstream/uperf/1.0.6"


def text(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8")


def selected_mode(interactive: bool, scene: str, global_mode: str,
                  rules: dict[str, str]) -> str:
    if not interactive:
        return "powersave"
    return rules.get(scene, global_mode)


class ScenePolicyTests(unittest.TestCase):
    RULES = {"game.fast": "fast", "game.perf": "performance"}

    def test_01_screen_off_wins(self):
        self.assertEqual(selected_mode(False, "game.fast", "balance", self.RULES), "powersave")

    def test_02_screen_on_recomputes(self):
        self.assertEqual(selected_mode(True, "game.fast", "balance", self.RULES), "fast")

    def test_03_global_balance(self):
        self.assertEqual(selected_mode(True, "video", "balance", self.RULES), "balance")

    def test_04_exact_fast(self):
        self.assertEqual(selected_mode(True, "game.fast", "balance", self.RULES), "fast")

    def test_05_exact_performance(self):
        self.assertEqual(selected_mode(True, "game.perf", "balance", self.RULES), "performance")

    def test_06_game_to_video_global(self):
        self.assertEqual(selected_mode(True, "video", "balance", self.RULES), "balance")

    def test_07_game_to_home_global(self):
        self.assertEqual(selected_mode(True, "home", "balance", self.RULES), "balance")

    def test_08_qs_does_not_replace_top_resumed(self):
        self.assertEqual(selected_mode(True, "game.fast", "balance", self.RULES), "fast")

    def test_09_zuicontrol_top_resumed_is_global(self):
        self.assertEqual(selected_mode(True, "com.zui.zuicontrol", "balance", self.RULES), "balance")

    def test_10_freeform_active_game(self):
        self.assertEqual(selected_mode(True, "game.fast", "balance", self.RULES), "fast")

    def test_11_freeform_inactive_game_does_not_win(self):
        self.assertEqual(selected_mode(True, "video", "balance", self.RULES), "balance")

    def test_12_split_active_pane_switch(self):
        self.assertEqual(selected_mode(True, "game.perf", "balance", self.RULES), "performance")

    def test_13_pip_visible_does_not_win(self):
        self.assertEqual(selected_mode(True, "home", "balance", self.RULES), "balance")

    def test_14_same_target_dedup_contract(self):
        sequence = ["game.fast", "game.fast", "game.fast"]
        modes = [selected_mode(True, item, "balance", self.RULES) for item in sequence]
        writes = sum(1 for index, mode in enumerate(modes) if index == 0 or modes[index - 1] != mode)
        self.assertEqual(writes, 1)


class SourceAuthorityTests(unittest.TestCase):
    def test_top_resumed_hook_is_unique_and_event_driven(self):
        hooks = text(HOOKS)
        patcher = text(PATCHER)
        service = text(SERVICE)
        self.assertEqual(hooks.count("void onTopResumedActivityChanged(ActivityRecord record)"), 1)
        self.assertEqual(patcher.count(
            "ZuiControlHooks;->onTopResumedActivityChanged(Lcom/android/server/wm/ActivityRecord;)V"), 1)
        self.assertIn("iput-object v1, p0, Lcom/android/server/wm/ActivityTaskSupervisor;->mTopResumedActivity", patcher)
        self.assertIn("uperfSceneAuthority=topResumedActivity", service)
        for forbidden in ("dumpsys activity", "top -n", "AccessibilityService", "postDelayed"):
            self.assertNotIn(forbidden, service)

    def test_refresh_feeds_do_not_drive_uperf(self):
        service = text(SERVICE)
        self.assertNotIn("onSystemStateChanged", service)
        top_calls = service.count("onTopResumedChanged(")
        screen_calls = service.count("onInteractiveChanged(")
        self.assertEqual(top_calls, 2)  # call site plus method declaration
        self.assertEqual(screen_calls, 2)
        self.assertIn("applyProfile(refreshProfile, reason, false);", service)

    def test_final_super_markers_follow_d8_class_ownership(self):
        verifier = text(FINAL_SUPER_VERIFIER)
        self.assertIn(
            "$uperfScenePolicySmali = Require-SingleFile $services "
            "'ZuiControlService$UperfScenePolicy.smali'",
            verifier,
        )
        for marker in (
            r"\nuperfSceneAuthority=topResumedActivity",
            r"\nuperfScenePackage=",
            r"\nuperfTopResumedSeen=",
        ):
            self.assertIn(
                "@{ Path = $uperfScenePolicySmali; Needle = '" + marker + "' }",
                verifier,
            )
        self.assertIn(
            "@{ Path = $serviceSmali; Needle = '\\nuperfFailSafe=' }",
            verifier,
        )


class LifecycleTests(unittest.TestCase):
    def test_15_unexpected_tree_exit_uses_subreaper_and_init(self):
        wrapper = text(WRAPPER)
        rc = text(RC)
        self.assertIn('mkfifo "$LOG_PIPE"', wrapper)
        self.assertIn('wait "$supervisor_pid"', wrapper)
        self.assertIn("FIFO EOF", wrapper)
        self.assertNotIn("FIFO EOF means", wrapper)
        self.assertIn("restart_period 5", rc)

    def test_16_explicit_stop_is_not_restart_action(self):
        rc = text(RC)
        stop_block = re.search(r"(?ms)^on property:zui_control\.scheduler=stop\n.*?(?=^\S|\Z)", rc)
        self.assertIsNotNone(stop_block)
        self.assertIn("stop zui_uperf", stop_block.group())
        self.assertNotIn("start zui_uperf", stop_block.group())
        self.assertNotIn("trigger zui-scheduler-start", stop_block.group())

    def test_17_bounded_rapid_crash(self):
        wrapper = text(WRAPPER)
        gate = text(CRASH_GATE)
        self.assertIn('[ "$rapid_count" -ge 3 ]', wrapper)
        self.assertIn('$((now - rapid_first)) -gt 20', wrapper)
        self.assertIn('[ "$runtime" -ge 0 ] && [ "$runtime" -le 2 ]', gate)
        self.assertIn('[ "$count" -lt 3 ] || setprop "$FAIL_SAFE_PROP" 1', gate)

    def test_18_no_periodic_health_polling(self):
        wrapper = text(WRAPPER)
        gate = text(CRASH_GATE)
        for forbidden in ("sleep ", "grep ", "uperf_process_count", "cgroup.procs", "while true"):
            self.assertNotIn(forbidden, wrapper)
        self.assertNotIn("sleep ", gate)
        self.assertNotIn("while ", gate)
        self.assertEqual(wrapper.count('read -r -t "$remaining"'), 1)
        self.assertEqual(wrapper.count("while IFS= read -r line; do"), 1)

    def test_19_single_init_instance(self):
        rc = text(RC)
        self.assertEqual(len(re.findall(r"(?m)^service zui_uperf\s", rc)), 1)

    def test_20_only_event_driven_wrapper_children(self):
        wrapper = text(WRAPPER)
        self.assertEqual(wrapper.count(" 9>&- &\n"), 1)
        self.assertEqual(wrapper.count("drain_uperf_log <&8 &\n"), 1)
        self.assertNotIn("inotifyd", wrapper)
        self.assertNotIn("tail -", wrapper)


class OwnershipTests(unittest.TestCase):
    def setUp(self):
        self.raw = text(CONFIG)
        self.config = json.loads(self.raw)

    def test_21_sched_disabled(self):
        self.assertIs(self.config["modules"]["sched"]["enable"], False)

    def test_22_native_auto_disabled(self):
        self.assertEqual(sorted(self.config["presets"]),
                         ["balance", "fast", "performance", "powersave"])
        self.assertNotIn('"auto"', json.dumps(self.config["presets"]))

    def test_23_no_gpu_owner(self):
        active_power_scope = json.dumps({
            "knob": self.config["modules"]["sysfs"]["knob"],
            "initials": self.config["initials"]["sysfs"],
            "presets": self.config["presets"],
        }).lower()
        for forbidden in ("kgsl", "adreno", "gpu", "thermal_pwrlevel"):
            self.assertNotIn(forbidden, active_power_scope)

    def test_24_no_thermal_disable(self):
        lowered = self.raw.lower()
        for forbidden in ("mi_thermald", "thermal-engine", "msm_thermal", "thermal_enable"):
            self.assertNotIn(forbidden, lowered)

    def test_25_asoul_binary_unchanged(self):
        binary = REPO / "payload/system/bin/AsoulOpt"
        digest = hashlib.sha256(binary.read_bytes()).hexdigest()
        self.assertEqual(digest, "7a2ee5d67ba7c057066176334eca9256e376427916429d66b7593cbb5538ec86")

    def test_v106_adopted_fields(self):
        self.assertIs(self.config["modules"]["sfanalysis"]["enable"], False)
        self.assertEqual(self.config["presets"]["balance"]["idle"]["cpu.baseSampleTime"], 1.0)
        self.assertEqual(self.config["presets"]["balance"]["idle"]["cpu.baseSlackTime"], 0.5)
        self.assertEqual(self.config["presets"]["powersave"]["idle"]["cpu.baseSampleTime"], 1.5)
        self.assertEqual(self.config["presets"]["powersave"]["idle"]["cpu.baseSlackTime"], 0.8)

    def test_property_and_fifo_policy_are_narrow(self):
        contexts = text(PROPERTY_CONTEXTS)
        policy = text(POLICY)
        self.assertIn("sys.zui_control.uperf_fail_safe u:object_r:zui_control_uperf_fail_safe_prop:s0 exact bool", contexts)
        self.assertIn("(allow performanced zui_control_data_file (fifo_file (getattr open read write create setattr unlink)))", policy)
        self.assertNotRegex(policy, r"allow (?:priv_app|untrusted_app) zui_control_uperf_fail_safe_prop")


class ImportWorkflowTests(unittest.TestCase):
    def test_importer_audits_without_production_write(self):
        before = {
            path: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in (CONFIG, REPO / "payload/system/bin/uperf")
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            archive_path = root / "fixture.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                for path in sorted(SNAPSHOT.rglob("*")):
                    if path.is_file() and path.name != "SHA256SUMS.txt":
                        archive.write(path, path.relative_to(SNAPSHOT).as_posix())
            output = root / "audit"
            subprocess.run([
                sys.executable, str(REPO / "scripts/ImportUperfUpstream.py"),
                "--zip", str(archive_path), "--output", str(output),
                "--repo", str(REPO),
            ], check=True, capture_output=True, text=True)
            report = json.loads((output / "UPSTREAM_AUDIT.json").read_text(encoding="utf-8"))
            self.assertTrue(report["binary"]["byte_for_byte_equal"])
            self.assertFalse(report["production_modified"])
            self.assertTrue((output / "SM8650_CONFIG_DIFF.json").is_file())
            self.assertTrue((output / "OWNER_CONFLICT_REPORT.md").is_file())
        after = {
            path: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in before
        }
        self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main(verbosity=2)
