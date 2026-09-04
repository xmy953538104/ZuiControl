#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import subprocess
import tempfile
import unittest


REPO = pathlib.Path(__file__).resolve().parents[2]
STATE = REPO / "framework_patch/src/services/com/zui/server/control/TopResumedNullState.java"
SERVICE = REPO / "framework_patch/src/services/com/zui/server/control/ZuiControlService.java"
HOOKS = REPO / "framework_patch/src/services/com/zui/server/control/ZuiControlHooks.java"
PATCHER = REPO / "scripts/build/PatchZuiControlFramework.py"
VERIFIER = REPO / "scripts/verify/VerifyZuiControlFinalSuper.ps1"
FIXTURE = pathlib.Path(__file__).with_name("TopResumedNullStateTest.java")


class TransitionalNullTests(unittest.TestCase):
    def test_state_machine_executes_all_required_fixtures(self):
        with tempfile.TemporaryDirectory() as output:
            subprocess.run(
                ["javac", "-source", "8", "-target", "8", "-d", output,
                 str(STATE), str(FIXTURE)],
                check=True,
            )
            completed = subprocess.run(
                ["java", "-cp", output,
                 "com.zui.server.control.TopResumedNullStateTest"],
                check=True,
                text=True,
                stdout=subprocess.PIPE,
            )
        for fixture in (
            "PACKAGE_NULL_PACKAGE_FIXTURE",
            "THREE_ACTIVITY_GAME_FIXTURE",
            "TRUE_NULL_FIXTURE",
            "A_TO_B_FIXTURE",
            "STALE_GENERATION_FIXTURE",
            "SCREEN_OFF_FIXTURE",
            "SCREEN_ON_FIXTURE",
            "DEDUP_FIXTURE",
        ):
            self.assertIn(f"{fixture}=PASS", completed.stdout)
        self.assertIn("TOP_RESUMED_TRANSITIONAL_NULL_STATE_MACHINE=PASS", completed.stdout)

    def test_integration_is_one_shot_authoritative_and_observable(self):
        service = SERVICE.read_text(encoding="utf-8")
        hooks = HOOKS.read_text(encoding="utf-8")
        patcher = PATCHER.read_text(encoding="utf-8")
        verifier = VERIFIER.read_text(encoding="utf-8")

        self.assertIn("TOP_RESUMED_NULL_REVALIDATE_DELAY_MS = 64L", service)
        self.assertEqual(service.count("postDelayed(mTopResumedNullRevalidation"), 1)
        self.assertIn("mTopResumedCallbackGeneration.incrementAndGet()", service)
        self.assertEqual(
            service.count("generation != mTopResumedCallbackGeneration.get()"), 2,
            "generation guard belongs to deferred revalidation, not valid callbacks",
        )
        self.assertNotIn("if (record == null) {\n            return;", service)
        self.assertIn("ActivityTaskSupervisor authority, ActivityRecord record", hooks)
        self.assertIn("getZuiControlTopResumedActivity()", service)
        self.assertIn("getTopDisplayFocusedRootTask()", patcher)
        self.assertIn("getTopResumedActivity()", patcher)
        self.assertIn("mGlobalLock", patcher)
        self.assertIn("boostPriorityForLockedSection", patcher)
        self.assertIn("resetPriorityAfterLockedSection", patcher)
        self.assertNotIn("mFocusedApp", service)
        self.assertNotIn("dumpsys", service)
        for marker in (
            "uperfTopResumedRawPackage=",
            "uperfTopResumedStablePackage=",
            "uperfTopResumedPendingNull=",
            "uperfTopResumedGeneration=",
            "uperfTopResumedRevalidateCount=",
            "uperfTopResumedLastRevalidateResult=",
            "topResumedValid",
            "topResumedNullDeferred",
            "topResumedRevalidated",
            "topResumedNullConfirmed",
        ):
            self.assertIn(marker, service)
        self.assertIn("getZuiControlTopResumedActivity", verifier)


if __name__ == "__main__":
    unittest.main(verbosity=2)
