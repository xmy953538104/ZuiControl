#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[2]
POLICY = REPO / "payload/patches/plat_sepolicy_zui_control.cil"
WRAPPER = REPO / "payload/system/bin/zui_uperf_service"
FILE_CONTEXTS = REPO / "payload/patches/plat_file_contexts_add.txt"
PROPERTY_CONTEXTS = REPO / "payload/patches/plat_property_contexts_add.txt"
VENDOR_POLICY = REPO / "payload/patches/vendor_sepolicy_zui_scheduler.cil"

OLD_FILE_ALLOW = (
    "(allow performanced zui_control_data_file (file "
    "(getattr open read write create append map watch watch_reads setattr unlink)))"
)
CORRECTED_FILE_ALLOW = (
    "(allow performanced zui_control_data_file (file "
    "(getattr open read write create append map watch watch_reads setattr unlink rename)))"
)
DIR_ALLOW = (
    "(allow performanced zui_control_data_file (dir "
    "(getattr open read search write add_name remove_name create setattr)))"
)
ATOMIC_PUBLISH = (
    "printf '%s\\n' \"$now\" > \"$READY_UPTIME.tmp\" || exit 1\n"
    "chmod 0600 \"$READY_UPTIME.tmp\" || exit 1\n"
    "mv -f \"$READY_UPTIME.tmp\" \"$READY_UPTIME\" || exit 1"
)


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def load_verifier():
    path = REPO / "scripts/VerifyUperfRuntimeAccess.py"
    spec = importlib.util.spec_from_file_location("uperf_ready_marker_gate", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def args(policy: Path) -> SimpleNamespace:
    return SimpleNamespace(
        mode="source",
        system_root=str(REPO / "payload/system"),
        file_contexts=str(FILE_CONTEXTS),
        property_contexts=str(PROPERTY_CONTEXTS),
        plat_policy=str(policy),
        vendor_policy=str(VENDOR_POLICY),
    )


class UperfReadyMarkerSelinuxTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.verifier = load_verifier()

    def test_01_frozen_wrapper_uses_atomic_ready_marker_publish(self) -> None:
        self.assertIn(ATOMIC_PUBLISH, text(WRAPPER))

    def test_02_access_graph_models_file_rename_and_parent_directory(self) -> None:
        report = self.verifier.verify(args(POLICY))
        ready_edges = [
            edge for edge in report["graph"]
            if edge["operation"] == "atomically publish ready marker"
        ]
        self.assertEqual(len(ready_edges), 1)
        self.assertEqual(ready_edges[0]["class"], "file")
        self.assertEqual(ready_edges[0]["permissions"], "rename")
        self.assertEqual(ready_edges[0]["policy_rule"], CORRECTED_FILE_ALLOW)
        self.assertIn(DIR_ALLOW, text(POLICY))
        for permission in ("search", "add_name", "remove_name"):
            self.assertIn(permission, DIR_ALLOW)

    def test_03_missing_rename_fixture_is_rejected(self) -> None:
        corrected = text(POLICY)
        self.assertEqual(corrected.count(CORRECTED_FILE_ALLOW), 1)
        missing_rename = corrected.replace(CORRECTED_FILE_ALLOW, OLD_FILE_ALLOW)
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "plat_sepolicy_missing_ready_marker_rename.cil"
            fixture.write_text(missing_rename, encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "atomically publish ready marker: missing"):
                self.verifier.verify(args(fixture))


if __name__ == "__main__":
    unittest.main(verbosity=2)
