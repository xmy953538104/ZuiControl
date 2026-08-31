#!/usr/bin/env python3
"""Host-only state-machine and production-binding tests for V20.4 WP1."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
ALLOWED_HZ = (60, 90, 120, 144, 165)


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def is_transient(package_name: str) -> bool:
    package_name = (package_name or "").lower()
    return not package_name or package_name in {
        "android",
        "com.android.systemui",
        "com.zui.game.service",
        "com.zui.zuicontrol",
    } or any(token in package_name for token in (
        "permissioncontroller",
        "packageinstaller",
        "resolver",
        "chooser",
        "inputmethod",
        "keyboard",
        "overlay",
    ))


class RefreshModel:
    """Small executable contract; Android integration is checked separately below."""

    def __init__(self) -> None:
        self.profiles: dict[str, int] = {}
        self.raw = ""
        self.raw_transient = True
        self.non_ime_raw = ""
        self.non_ime_transient = True
        self.current = ""
        self.last_business = ""
        self.desired = "default"
        self.attempted = ""
        self.applied = ""
        self.target_hz = 120
        self.attempted_hz = 0
        self.applied_hz = 0
        self.disable_mask = 0
        self.request_owned = False
        self.vote_owned = False
        self.peak_owned = False
        self.apply_count = 0
        self.skip_count = 0
        self.last_reason = "init"
        self.last_error = ""
        self.fail_stage = ""
        self.uperf = "running"
        self.asoul = "running"
        self.command = "available"

    def hz_for(self, package_name: str) -> int:
        return self.profiles.get(package_name, 120)

    def focus(self, package_name: str) -> str:
        self.raw = package_name or ""
        self.raw_transient = is_transient(self.raw)
        self.non_ime_raw = self.raw
        self.non_ime_transient = self.raw_transient
        if not self.raw_transient:
            self.current = self.raw
            self.last_business = self.raw
        return self.reconcile("focusTransient" if self.raw_transient else "focus")

    def window_focus(self, package_name: str, activity_package: str) -> str:
        self.raw = package_name or ""
        self.raw_transient = (not self.raw or is_transient(self.raw)
                              or self.raw != activity_package)
        self.non_ime_raw = self.raw
        self.non_ime_transient = self.raw_transient
        return self.reconcile("windowFocusTransient" if self.raw_transient
                              else "windowFocus")

    def set_ime_visible(self, visible: bool) -> str:
        if visible:
            self.raw = "@ime"
            self.raw_transient = True
            return self.reconcile("imeVisibleTransient")
        self.raw = self.non_ime_raw
        self.raw_transient = self.non_ime_transient
        return self.reconcile("imeHiddenTransient" if self.raw_transient else "imeHidden")

    def reconcile(self, reason: str, force: bool = False) -> str:
        self.desired = "default" if self.raw_transient else self.raw
        self.target_hz = 120 if self.desired == "default" else self.hz_for(self.raw)
        if self.disable_mask:
            self.last_reason = f"{reason}:disabled"
            return "disabled"
        if self.target_hz not in ALLOWED_HZ:
            self.last_error = f"no_display_mode_{self.target_hz}"
            self.last_reason = f"{reason}:failedBeforeMutation"
            return "failed"
        if (not force and self.request_owned and self.vote_owned
                and self.applied_hz == self.target_hz):
            self.applied = self.desired
            self.skip_count += 1
            self.last_error = ""
            self.last_reason = f"{reason}:skipSame"
            return "skipSame"
        self.attempted = self.desired
        self.attempted_hz = self.target_hz
        if self.fail_stage == "peak":
            return self.fail_after_mutation(reason, "peak_failed")
        self.peak_owned = True
        if self.fail_stage == "vote":
            return self.fail_after_mutation(reason, "vote_failed")
        self.vote_owned = True
        if self.fail_stage == "app_request":
            return self.fail_after_mutation(reason, "app_request_failed")
        self.request_owned = True
        self.applied = self.desired
        self.applied_hz = self.target_hz
        self.apply_count += 1
        self.last_error = ""
        self.last_reason = f"{reason}:applied"
        return "applied"

    def fail_after_mutation(self, reason: str, error: str) -> str:
        self.request_owned = False
        self.vote_owned = False
        self.peak_owned = False
        self.applied = ""
        self.applied_hz = 0
        self.last_error = error
        self.last_reason = f"{reason}:failedAfterMutation"
        return "failed"

    def set_current_profile(self, hz: int) -> str:
        package_name = self.last_business or self.current
        if not package_name:
            return "no_current_scene"
        if hz not in ALLOWED_HZ:
            return "unsupported"
        if hz == 120:
            self.profiles.pop(package_name, None)
        else:
            self.profiles[package_name] = hz
        if self.raw == package_name and not self.raw_transient:
            return self.reconcile("binder")
        return "savedOnly"

    def set_explicit_profile(self, package_name: str, hz: int) -> str:
        if is_transient(package_name):
            return "transient_package_not_configurable"
        if hz not in ALLOWED_HZ:
            return "unsupported"
        self.profiles[package_name] = hz
        if self.raw == package_name and not self.raw_transient:
            return self.reconcile("binder")
        return "savedOnly"

    def set_disable_mask(self, mask: int) -> str:
        was_disabled = bool(self.disable_mask)
        self.disable_mask = mask
        disabled = bool(mask)
        if not was_disabled and disabled:
            self.request_owned = False
            self.vote_owned = False
            self.peak_owned = False
            self.applied = ""
            self.applied_hz = 0
            self.last_reason = "propertyDisable:released"
            return "released"
        if was_disabled and not disabled:
            return self.reconcile("propertyEnable", force=True)
        self.last_reason = f"propertyDisableMaskChanged:{mask}"
        return "maskOnly"


class RefreshStateModelTest(unittest.TestCase):
    def test_zuicontrol_is_neutral_foreground_not_inheritance(self) -> None:
        model = RefreshModel()
        model.profiles["app.a"] = 90
        self.assertEqual("applied", model.focus("app.a"))
        self.assertEqual(("app.a", "app.a", 90),
                         (model.last_business, model.applied, model.applied_hz))

        self.assertEqual("applied", model.focus("com.zui.zuicontrol"))
        self.assertEqual("app.a", model.current)
        self.assertEqual("app.a", model.last_business)
        self.assertEqual(("default", "default", 120),
                         (model.desired, model.applied, model.applied_hz))

        self.assertEqual("applied", model.focus("app.a"))
        self.assertEqual(("app.a", 90), (model.applied, model.applied_hz))

    def test_qs_edits_last_business_but_stays_neutral(self) -> None:
        model = RefreshModel()
        model.profiles["launcher"] = 90
        model.focus("launcher")
        model.focus("com.android.systemui")

        self.assertEqual("savedOnly", model.set_current_profile(144))
        self.assertEqual(144, model.profiles["launcher"])
        self.assertEqual(("default", 120), (model.applied, model.applied_hz))
        self.assertNotIn("com.android.systemui", model.profiles)

        model.focus("launcher")
        self.assertEqual(("launcher", 144), (model.applied, model.applied_hz))

    def test_all_transient_classes_use_default_without_learning(self) -> None:
        model = RefreshModel()
        model.profiles["app.b"] = 90
        model.focus("app.b")
        for package_name in (
            "com.android.systemui",
            "com.zui.zuicontrol",
            "com.example.inputmethod.latin",
            "com.android.permissioncontroller",
            "com.android.intentresolver",
            "com.example.overlay",
            "",
        ):
            model.focus(package_name)
            self.assertEqual("app.b", model.last_business)
            self.assertEqual(("default", 120), (model.desired, model.applied_hz))
            self.assertNotIn(package_name, model.profiles)

    def test_empty_and_unknown_window_owner_are_neutral(self) -> None:
        model = RefreshModel()
        model.profiles["app.a"] = 90
        model.focus("app.a")
        for owner in ("", "vendor.popup.window"):
            model.window_focus(owner, "app.a")
            self.assertEqual(("default", 120), (model.desired, model.applied_hz))
            self.assertEqual("app.a", model.last_business)

    def test_ime_hide_restores_pre_ime_transient_window(self) -> None:
        model = RefreshModel()
        model.profiles["app.a"] = 90
        model.focus("app.a")
        model.window_focus("com.android.systemui", "app.a")
        model.set_ime_visible(True)
        model.set_ime_visible(False)
        self.assertEqual("com.android.systemui", model.raw)
        self.assertEqual(("default", 120), (model.desired, model.applied_hz))

    def test_120_to_default_is_strict_dedup(self) -> None:
        model = RefreshModel()
        model.focus("unconfigured.app")
        apply_count = model.apply_count
        self.assertEqual("skipSame", model.focus("com.zui.zuicontrol"))
        self.assertEqual(apply_count, model.apply_count)
        self.assertEqual("default", model.applied)
        self.assertEqual(1, model.skip_count)

    def test_non_120_round_trips_are_real_applies(self) -> None:
        model = RefreshModel()
        model.profiles["app.a"] = 90
        model.focus("app.a")
        base = model.apply_count
        for _ in range(100):
            model.focus("com.zui.zuicontrol")
            model.focus("app.a")
        self.assertEqual(200, model.apply_count - base)

    def test_kill_switch_releases_and_reenables_current_raw(self) -> None:
        model = RefreshModel()
        model.profiles["app.b"] = 90
        model.focus("app.b")
        self.assertEqual("released", model.set_disable_mask(2))
        self.assertFalse(model.request_owned or model.vote_owned or model.peak_owned)
        self.assertEqual(("", 0), (model.applied, model.applied_hz))
        self.assertEqual("applied", model.set_disable_mask(0))
        self.assertEqual(("app.b", 90), (model.applied, model.applied_hz))

        model.focus("com.android.systemui")
        model.set_disable_mask(1)
        self.assertEqual("applied", model.set_disable_mask(0))
        self.assertEqual(("default", 120), (model.applied, model.applied_hz))

    def test_global_disable_does_not_touch_other_planes(self) -> None:
        model = RefreshModel()
        before = (model.uperf, model.asoul, model.command)
        model.focus("app.a")
        model.set_disable_mask(1)
        self.assertEqual(before, (model.uperf, model.asoul, model.command))

    def test_five_rates_and_unsupported_are_exact(self) -> None:
        for hz in ALLOWED_HZ:
            model = RefreshModel()
            self.assertEqual("savedOnly", model.set_explicit_profile("app.rate", hz))
            model.focus("app.rate")
            self.assertEqual(hz, model.applied_hz)

        model = RefreshModel()
        model.focus("app.rate")
        applied = (model.applied, model.applied_hz, model.apply_count)
        self.assertEqual("unsupported", model.set_explicit_profile("app.rate", 75))
        self.assertEqual(applied, (model.applied, model.applied_hz, model.apply_count))

    def test_failure_does_not_pollute_applied(self) -> None:
        model = RefreshModel()
        model.profiles["app.a"] = 90
        model.focus("app.a")
        model.fail_stage = "app_request"
        result = model.focus("com.zui.zuicontrol")
        self.assertEqual("failed", result)
        self.assertEqual(("default", 120), (model.desired, model.attempted_hz))
        self.assertEqual(("", 0), (model.applied, model.applied_hz))
        self.assertTrue(model.last_error)
        self.assertEqual(1, model.apply_count)


class ProductionBindingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.service = read(
            "framework_patch/src/services/com/zui/server/control/ZuiControlService.java"
        )
        cls.hooks = read(
            "framework_patch/src/services/com/zui/server/control/ZuiControlHooks.java"
        )
        cls.framework_patcher = read("scripts/PatchZuiControlFramework.py")
        cls.client = read("app/src/main/java/com/zui/zuicontrol/ZuiControlClient.kt")
        cls.main = read("app/src/main/java/com/zui/zuicontrol/MainActivity.kt")
        cls.tile = read("app/src/main/java/com/zui/zuicontrol/ZuiControlTileService.kt")
        cls.quick = read("app/src/main/java/com/zui/zuicontrol/ZuiControlQuickService.kt")
        cls.system_properties = read("framework_patch/stubs/android/os/SystemProperties.java")
        cls.wm_internal = read(
            "framework_patch/stubs/com/android/server/wm/WindowManagerInternal.java"
        )

    def test_transient_is_neutral_and_not_profile_owner(self) -> None:
        self.assertNotIn("controlPanel", self.service)
        self.assertNotIn("profileFor(APP_PACKAGE", self.service)
        self.assertIn('source + "Transient"', self.service)
        self.assertIn("refreshProfile = neutralProfile(userId);", self.service)
        self.assertIn('new Profile(DEFAULT_SCENE, userId, 120, 0, "DISPLAY_ONLY")',
                      self.service)
        self.assertIn('error=transient_package_not_configurable', self.service)
        self.assertIn("if (!isTransientPackage(parts[2]))", self.service)
        self.assertIn("mImeVisible || mRawFocusedPackage.isEmpty()", self.service)

    def test_window_and_ime_edges_are_lightweight_posts(self) -> None:
        self.assertIn("onFocusedWindowChanged", self.hooks)
        self.assertIn("onImeVisibilityChanged", self.hooks)
        self.assertIn("mWorker.post(new Runnable()", self.service)
        self.assertIn("onFocusedWindowChanged(Ljava/lang/String;I)V", self.framework_patcher)
        self.assertIn("onImeVisibilityChanged(Ljava/lang/String;ZI)V", self.framework_patcher)
        self.assertIn("mImeControlTarget", self.framework_patcher)
        for forbidden in ("Thread.sleep", "Runtime.getRuntime", "ProcessBuilder"):
            self.assertNotIn(forbidden, self.hooks)

    def test_configuration_and_physical_axes_are_separate(self) -> None:
        for field in (
            "editableScenePackage",
            "editableDisplayHz",
            "desiredScenePackage",
            "attemptedScenePackage",
            "appliedScenePackage",
            "attemptedDisplayHz",
            "appliedDisplayHz",
            "physicalDisplayHz",
            "lastApplyReason",
            "lastApplyError",
            "refreshApplyCount",
            "skipSameCount",
        ):
            self.assertIn(f'"\\n{field}="', self.service)
        self.assertIn("isForegroundBusinessPackage(pkg, mCurrentUserId)", self.service)
        self.assertIn('return stateInt("editableDisplayHz")', self.client)
        self.assertIn("ZuiControlClient.editableDisplayHz()", self.tile)
        self.assertIn("ZuiControlClient.editableDisplayHz()", self.quick)

    def test_apply_success_order_and_failure_cleanup(self) -> None:
        apply_method = self.service[
            self.service.index("private String applyProfile("):
            self.service.index("private String failApplyBeforeMutation(")
        ]
        self.assertLess(
            apply_method.index("mDesiredScenePackage = profile.packageName"),
            apply_method.index("mAttemptedScenePackage = profile.packageName"),
        )
        platform_call = apply_method.index("dmi.setDisplayProperties")
        success_write = apply_method.index("mAppliedScenePackage = profile.packageName", platform_call)
        self.assertLess(platform_call, success_write)
        self.assertIn("failApplyAfterMutation", apply_method)
        self.assertIn("clearAppliedState();", self.service)

    def test_kill_switch_is_event_driven_without_polling(self) -> None:
        self.assertIn("SystemProperties.addChangeCallback", self.service)
        self.assertIn("addChangeCallback", self.system_properties)
        self.assertIn("enqueueRefreshDisableMask(readRefreshDisableMask())", self.service)
        self.assertIn("onRefreshPropertiesChanged(disableMask)", self.service)
        self.assertNotIn("removeCallbacks(mRefreshPropertyRunnable)", self.service)
        self.assertIn("readRefreshDisableMask()", self.service)
        property_section = self.service[
            self.service.index("private void registerRefreshPropertyObserver"):
            self.service.index("private void registerScreenObserver")
        ]
        for forbidden in ("postDelayed", "sleep(", "Timer", "while ("):
            self.assertNotIn(forbidden, property_section)

    def test_cleanup_uses_owner_safe_vote_and_wm_handoff(self) -> None:
        self.assertIn('"updateGlobalVote", int.class, voteClass', self.service)
        self.assertNotIn('"updateVote", int.class, int.class', self.service)
        self.assertIn("updateGlobalRenderVote(dmi, null)", self.service)
        self.assertIn("requestTraversalFromDisplayManager()", self.service)
        self.assertIn("appRequestHandoffPending", self.service)
        self.assertIn("requestTraversalFromDisplayManager", self.wm_internal)
        self.assertEqual(1, self.service.count("dmi.setDisplayProperties("))
        self.assertNotIn("SETTING_MIN_REFRESH_RATE", self.service)

    def test_peak_is_compare_and_restore(self) -> None:
        self.assertIn("mPeakRestoreValue = restoreValue", self.service)
        self.assertIn("safe(mPeakLastWritten).equals(current)", self.service)
        self.assertIn("writePeakSetting(mPeakRestoreValue)", self.service)
        self.assertIn('mPeakReleaseStatus = "externalPreserved"', self.service)

    def test_window_context_and_storage_fail_closed(self) -> None:
        self.assertIn("mRawFocusTransient", self.service)
        self.assertIn("volatile FocusSnapshot mLatestFocus", self.service)
        self.assertIn("mLatestNonImeFocusedPackage", self.service)
        self.assertIn("mNonImeFocusedPackage", self.service)
        self.assertIn("displayId != Display.DEFAULT_DISPLAY", self.service)
        self.assertIn("private boolean saveProfiles()", self.service)
        self.assertIn('return "ok=0\\nerror=" + mLastError;', self.service)
        self.assertIn("profileSaved=1", self.service)
        self.assertIn("userId: Int = currentUserId()", self.client)
        self.assertIn("userId == ZuiControlClient.currentUserId()", self.main)


if __name__ == "__main__":
    unittest.main(verbosity=2)
