#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[3]
CONFIG = REPO / "payload/system/etc/zui_control/uperf-sm8650.json"
UPSTREAM_CONFIG = REPO / "upstream/uperf/1.0.6/config/sdm8g3.json"
POLICY = REPO / "payload/patches/plat_sepolicy_zui_control.cil"
WRAPPER = REPO / "payload/system/bin/zui_uperf_service"
CRASH_GATE = REPO / "payload/system/etc/zui_control/zui_uperf_crash_gate.sh"
FIXTURE = REPO / "tests/fixtures/uperf_sfanalysis_failure_excerpt.txt"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_readback_module():
    path = REPO / "tests/flash/VerifyFixedSevenReadback.py"
    spec = importlib.util.spec_from_file_location("readback_gate", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def load_access_verifier_module():
    path = REPO / "tests/selinux/TestUperfRuntimeAccess.py"
    spec = importlib.util.spec_from_file_location("uperf_access_gate", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class UperfStartupBoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.config = json.loads(text(CONFIG))
        cls.upstream = json.loads(text(UPSTREAM_CONFIG))
        cls.policy = text(POLICY)
        cls.wrapper = text(WRAPPER)
        cls.crash_gate = text(CRASH_GATE)

    def test_01_sfanalysis_root_cause_chain_is_preserved(self) -> None:
        binary = (REPO / "payload/system/bin/uperf").read_bytes()
        runtime = text(FIXTURE)
        self.assertIs(self.upstream["modules"]["sfanalysis"]["enable"], True)
        for marker in (b"SfAnalysisListener", b"/system/bin/surfaceflinger", b"sfanalysis"):
            self.assertIn(marker, binary)
        self.assertEqual(runtime.count('comm="uperf" name="surfaceflinger"'), 2)
        self.assertIn("surfaceflinger_exec:s0 tclass=file permissive=0", runtime)

    def test_02_final_sfanalysis_is_false(self) -> None:
        self.assertIs(self.config["modules"]["sfanalysis"]["enable"], False)

    def test_03_four_idle_tuning_fields_are_unchanged(self) -> None:
        presets = self.config["presets"]
        self.assertEqual(presets["balance"]["idle"]["cpu.baseSampleTime"], 1.0)
        self.assertEqual(presets["balance"]["idle"]["cpu.baseSlackTime"], 0.5)
        self.assertEqual(presets["powersave"]["idle"]["cpu.baseSampleTime"], 1.5)
        self.assertEqual(presets["powersave"]["idle"]["cpu.baseSlackTime"], 0.8)

    def test_04_proc_uptime_correction_is_unchanged(self) -> None:
        supervisor = text(REPO / "native/zui_uperf_supervisor.c")
        self.assertNotIn("< /proc/uptime", self.wrapper)
        self.assertIn("clock_gettime(CLOCK_MONOTONIC", supervisor)
        self.assertIn("(allow performanced proc_uptime (file (getattr open read)))", self.policy)

    def test_05_scheduler_active_guard_remains_removed(self) -> None:
        self.assertNotIn("sys.zui_control.scheduler_active", self.crash_gate)
        self.assertNotRegex(
            self.policy,
            r"\(allow\s+shell\s+zui_control_scheduler_active_prop\s+\(file\s+",
        )

    def test_06_fail_safe_design_is_unchanged(self) -> None:
        supervisor = text(REPO / "native/zui_uperf_supervisor.c")
        self.assertIn("STARTUP_TIMEOUT_MS 20000L", supervisor)
        self.assertNotIn("rapid_count", self.wrapper)
        self.assertIn('[ "$count" -lt 3 ] || setprop "$FAIL_SAFE_PROP" 1', self.crash_gate)

    def test_07_no_surfaceflinger_exec_file_allow_was_added(self) -> None:
        self.assertNotRegex(
            self.policy,
            r"\(allow\s+performanced(?:_34_0)?\s+[^\s()]*surfaceflinger_exec[^\s()]*\s+\(file\s+",
        )

    def test_08_no_broad_proc_allow_was_added(self) -> None:
        self.assertNotRegex(
            self.policy,
            r"\(allow\s+performanced\s+(?:proc|proc_type|fs_type)\s+\(file\s+",
        )

    def test_09_fifo_and_shell_polling_are_removed(self) -> None:
        self.assertIn('exec "$SUPERVISOR" "$CONFIG" "$LOG" "$READY_UPTIME"', self.wrapper)
        self.assertNotIn("mkfifo", self.wrapper)
        self.assertNotIn("LOG_PIPE", self.wrapper)
        self.assertNotIn("drain_uperf_log", self.wrapper)
        for token in ("sleep ", "pidof uperf", "killall", "uperf_process_count", "grep "):
            self.assertNotIn(token, self.wrapper)

    def test_10_top_resumed_remains_the_sole_scene_authority(self) -> None:
        service = text(
            REPO / "framework_patch/src/services/com/zui/server/control/ZuiControlService.java")
        hooks = text(
            REPO / "framework_patch/src/services/com/zui/server/control/ZuiControlHooks.java")
        self.assertIn("uperfSceneAuthority=topResumedActivity", service)
        self.assertIn("getZuiControlTopResumedActivity()", service)
        self.assertIn("ActivityTaskSupervisor authority, ActivityRecord record", hooks)
        self.assertNotIn("mFocusedApp", service)
        self.assertNotIn("dumpsys activity", service)

    def test_11_uperf_binary_is_unchanged(self) -> None:
        self.assertEqual(
            sha256(REPO / "payload/system/bin/uperf"),
            "f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8",
        )

    def test_12_sched_stays_false(self) -> None:
        self.assertIs(self.config["modules"]["sched"]["enable"], False)

    def test_13_native_auto_is_not_a_production_owner(self) -> None:
        self.assertIs(self.config["modules"]["sched"]["enable"], False)
        self.assertEqual(
            self.config["modules"]["switcher"]["switchInode"],
            "/data/vendor/zui_control/uperf/effective_powermode.txt",
        )
        self.assertNotIn("auto", self.config["presets"])

    def test_14_refresh_artifact_is_unchanged(self) -> None:
        self.assertEqual(
            sha256(REPO / "payload/system/etc/init/zui_refresh_kill_switch.rc"),
            "0161a9980777b4313d0a5935b0e861e1afea86c5b4eb07ef5b88e44f20143c62",
        )

    def test_15_asoulopt_is_unchanged(self) -> None:
        self.assertEqual(
            sha256(REPO / "payload/system/bin/AsoulOpt"),
            "7a2ee5d67ba7c057066176334eca9256e376427916429d66b7593cbb5538ec86",
        )

    def test_16_qdl_flag_alone_cannot_pass(self) -> None:
        verifier = text(REPO / "tests/flash/VerifyFixedSevenReadback.py")
        self.assertIn("qdl --read-back-verify flag alone cannot satisfy this gate", verifier)
        self.assertIn('"dump-part" not in manifest.get("method", "")', verifier)

    def test_17_qdl_evidence_requires_real_bytes_and_hash(self) -> None:
        module = load_readback_module()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            programs = []
            evidence = []
            for index, label in enumerate(module.LABELS):
                filename = f"image{index}.img"
                data = bytes([index]) * 4096
                (root / filename).write_bytes(data)
                digest = hashlib.sha256(data).hexdigest()
                programs.append(
                    f'<program label="{label}" filename="{filename}" '
                    f'num_partition_sectors="1" SECTOR_SIZE_IN_BYTES="4096" '
                    f'physical_partition_number="{index % 2}" />'
                )
                evidence.append(
                    {
                        "label": label,
                        "physical_partition_number": index % 2,
                        "bytes": 4096,
                        "source_sha256": digest,
                        "physical_readback_sha256": digest,
                        "result": "PASS",
                    }
                )
            (root / "rawprogram_zuicontrol.xml").write_text(
                "<data>" + "".join(programs) + "</data>", encoding="utf-8"
            )
            manifest = root / "manifest.json"
            manifest.write_text(
                json.dumps(
                    {
                        "qdl_flag_only_accepted_as_proof": False,
                        "method": "qdl-rs dump-part host bytes",
                        "fixed_seven": evidence,
                        "result": "PHYSICAL_PARTITION_READBACK=PASS",
                    }
                ),
                encoding="utf-8",
            )
            self.assertTrue(module.verify(root, manifest)["ok"])
            evidence[0]["physical_readback_sha256"] = "0" * 64
            manifest.write_text(
                json.dumps(
                    {
                        "qdl_flag_only_accepted_as_proof": False,
                        "method": "qdl-rs dump-part host bytes",
                        "fixed_seven": evidence,
                        "result": "PHYSICAL_PARTITION_READBACK=PASS",
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "physical partition hash mismatch"):
                module.verify(root, manifest)

    def test_18_previous_failed_runtime_fixture_is_detectable(self) -> None:
        runtime = text(FIXTURE)
        self.assertEqual(runtime.count("surfaceflinger_exec:s0"), 2)
        self.assertIn("SERVICE_RAPID_CRASH_COUNTER=3", runtime)
        self.assertIn("UPERF_STARTUP_STABILITY_GATE=FAIL", runtime)

    def test_19_config_activated_module_review_is_mandatory(self) -> None:
        verifier = text(REPO / "tests/selinux/TestUperfRuntimeAccess.py")
        for module in ("sfanalysis", "input", "switcher", "sysfs", "sched"):
            self.assertIn(f'"module": "{module}"', verifier)
        self.assertIn('"closed_source_config_modules": "PARTIAL_STATIC_REVIEW"', verifier)

    def test_20_readback_gate_requires_exact_bytes_and_hashes(self) -> None:
        verifier = text(REPO / "tests/flash/VerifyFixedSevenReadback.py")
        for contract in (
            "image does not fill the programmed extent",
            "physical byte count mismatch",
            "source image hash mismatch",
            "physical partition hash mismatch",
        ):
            self.assertIn(contract, verifier)

    def test_21_upstream_true_current_false_is_explicit(self) -> None:
        self.assertIs(self.upstream["modules"]["sfanalysis"]["enable"], True)
        self.assertIs(self.config["modules"]["sfanalysis"]["enable"], False)

    def test_22_surfaceflinger_gate_targets_denied_exec_file_type(self) -> None:
        verifier = load_access_verifier_module()
        pattern = verifier.BROAD_POLICY_PATTERNS[
            "performanced SurfaceFlinger executable file access"
        ]
        self.assertNotRegex(
            "(allow performanced surfaceflinger (file (read getattr open)))",
            pattern,
        )
        self.assertRegex(
            "(allow performanced surfaceflinger_exec (file (read getattr open)))",
            pattern,
        )
        self.assertRegex(
            "(allow performanced_34_0 vendor_surfaceflinger_exec (file (read)))",
            pattern,
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
