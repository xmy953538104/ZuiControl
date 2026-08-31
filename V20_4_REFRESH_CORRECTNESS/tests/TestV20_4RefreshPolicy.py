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
        "com.lenovo.screensplit",
        "com.zui.freeform.sidebar",
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

    def __init__(self, persisted_disable_mask: int = 0) -> None:
        self.profiles: dict[str, int] = {}
        self.activity = ""
        self.window_seen = False
        self.window_empty = False
        self.empty_focus_count = 0
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
        self.property_disable_mask = persisted_disable_mask
        self.disable_mask = persisted_disable_mask
        self.observed_disable_hint = 0
        self.request_owned = False
        self.handoff_pending = False
        self.vote_owned = False
        self.peak_owned = False
        self.apply_count = 0
        self.apply_history: list[int] = []
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
        """Convenience for a correlated Activity + window-focus edge."""
        self.activity = package_name or ""
        self.window_seen = True
        self.window_empty = False
        return self._set_physical_focus(package_name, "windowFocus")

    def activity_focus(self, package_name: str) -> str:
        self.activity = package_name or ""
        if self.window_seen:
            return "metadataOnly"
        return self._set_physical_focus(package_name, "focus")

    def window_focus(self, package_name: str) -> str:
        if not package_name:
            if not self.window_empty:
                self.empty_focus_count += 1
            self.window_empty = True
            return "emptyFocusRetained"
        self.window_empty = False
        self.window_seen = True
        return self._set_physical_focus(package_name, "windowFocus")

    def _set_physical_focus(self, package_name: str, reason: str) -> str:
        self.raw = package_name or ""
        self.raw_transient = is_transient(self.raw)
        self.non_ime_raw = self.raw
        self.non_ime_transient = self.raw_transient
        if not self.raw_transient:
            self.current = self.raw
            self.last_business = self.raw
        suffix = "Transient" if self.raw_transient else ""
        return self.reconcile(f"{reason}{suffix}")

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
        self.handoff_pending = False
        self.applied = self.desired
        self.applied_hz = self.target_hz
        self.apply_count += 1
        self.apply_history.append(self.target_hz)
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
            had_request = self.request_owned
            self.request_owned = False
            self.handoff_pending = had_request
            self.vote_owned = False
            self.peak_owned = False
            self.applied = ""
            self.applied_hz = 0
            outcome = "releaseRequested" if had_request else "released"
            self.last_reason = f"propertyDisable:{outcome}"
            return outcome
        if was_disabled and not disabled:
            return self.reconcile("propertyEnable", force=True)
        self.last_reason = f"propertyDisableMaskChanged:{mask}"
        return "maskOnly"

    def raw_setprop(self, mask: int) -> str:
        """Property-area truth changes, but no process callback is implied."""
        self.property_disable_mask = mask
        return "propertyOnly"

    def sysprops_poke(self) -> str:
        return self.consume_property_hint(
            self.property_disable_mask, self.property_disable_mask
        )

    def binder_set_refresh_enabled(self, enabled: bool) -> str:
        if enabled:
            self.property_disable_mask &= ~2
        else:
            self.property_disable_mask |= 2
        return self.set_disable_mask(self.property_disable_mask)

    def consume_property_hint(self, observed_mask: int, real_mask: int) -> str:
        """A generic callback is only a wakeup; worker-side truth wins."""
        self.observed_disable_hint = observed_mask
        return self.set_disable_mask(real_mask)


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
            "com.lenovo.screensplit",
            "com.zui.freeform.sidebar",
        ):
            model.focus(package_name)
            self.assertEqual("app.b", model.last_business)
            self.assertEqual(("default", 120), (model.desired, model.applied_hz))
            self.assertNotIn(package_name, model.profiles)

    def test_empty_window_retains_last_nonempty_owner(self) -> None:
        model = RefreshModel()
        model.profiles["app.a"] = 90
        model.focus("app.a")
        before = (model.raw, model.desired, model.applied_hz, model.apply_count)
        self.assertEqual("emptyFocusRetained", model.window_focus(""))
        self.assertEqual(before,
                         (model.raw, model.desired, model.applied_hz,
                          model.apply_count))
        self.assertEqual((True, 1), (model.window_empty, model.empty_focus_count))

    def test_known_overlay_window_owner_is_neutral(self) -> None:
        model = RefreshModel()
        model.profiles["app.a"] = 90
        model.focus("app.a")
        model.window_focus("vendor.example.overlay")
        self.assertEqual(("default", 120), (model.desired, model.applied_hz))
        self.assertEqual("app.a", model.last_business)

    def test_activity_b_then_empty_window_then_b_has_no_default_apply(self) -> None:
        model = RefreshModel()
        model.profiles.update({"app.a": 90, "app.b": 60})
        model.focus("app.a")
        self.assertEqual("metadataOnly", model.activity_focus("app.b"))
        self.assertEqual("emptyFocusRetained", model.window_focus(""))
        self.assertEqual(("app.a", "app.a", 90, [90]),
                         (model.raw, model.desired, model.applied_hz,
                          model.apply_history))
        self.assertEqual("applied", model.window_focus("app.b"))
        self.assertEqual(("app.b", 60, [90, 60], 1),
                         (model.desired, model.applied_hz,
                          model.apply_history, model.empty_focus_count))

    def test_empty_window_with_unknown_activity_retains_last_policy(self) -> None:
        model = RefreshModel()
        model.profiles.update({"app.a": 90, "app.b": 60})
        model.focus("app.a")
        self.assertEqual("metadataOnly", model.activity_focus(""))
        self.assertEqual("emptyFocusRetained", model.window_focus(""))
        self.assertEqual(("app.a", 90, [90]),
                         (model.desired, model.applied_hz, model.apply_history))
        model.window_focus("app.b")
        self.assertEqual(("app.b", 60, [90, 60]),
                         (model.desired, model.applied_hz, model.apply_history))

    def test_ime_hide_restores_pre_ime_transient_window(self) -> None:
        model = RefreshModel()
        model.profiles["app.a"] = 90
        model.focus("app.a")
        model.window_focus("com.android.systemui")
        model.set_ime_visible(True)
        model.set_ime_visible(False)
        self.assertEqual("com.android.systemui", model.raw)
        self.assertEqual(("default", 120), (model.desired, model.applied_hz))

    def test_activity_metadata_behind_ime_does_not_replace_underlying_window(self) -> None:
        model = RefreshModel()
        model.profiles.update({"app.a": 90, "app.b": 60})
        model.focus("app.a")
        model.set_ime_visible(True)
        self.assertEqual("metadataOnly", model.activity_focus("app.b"))
        self.assertEqual(("app.a", False),
                         (model.non_ime_raw, model.non_ime_transient))
        model.set_ime_visible(False)
        self.assertEqual(("app.a", "app.a", 90),
                         (model.raw, model.desired, model.target_hz))
        model.window_focus("app.b")
        self.assertEqual(("app.b", 60), (model.desired, model.target_hz))

    def test_activity_first_keeps_existing_window_until_window_edge(self) -> None:
        model = RefreshModel()
        model.profiles.update({"app.a": 90, "app.b": 60})
        model.focus("app.a")
        before = (model.desired, model.target_hz, model.apply_count,
                  list(model.apply_history))

        self.assertEqual("metadataOnly", model.activity_focus("app.b"))
        self.assertEqual(before, (model.desired, model.target_hz,
                                  model.apply_count, model.apply_history))
        self.assertEqual(("app.a", False), (model.raw, model.raw_transient))

        self.assertEqual("applied", model.window_focus("app.b"))
        self.assertEqual(("app.b", 60), (model.desired, model.target_hz))
        self.assertEqual([90, 60], model.apply_history)

    def test_systemui_stays_neutral_while_background_activity_changes(self) -> None:
        model = RefreshModel()
        model.profiles.update({"app.a": 90, "app.b": 60})
        model.focus("app.a")
        model.window_focus("com.android.systemui")
        neutral_count = model.apply_count

        self.assertEqual("metadataOnly", model.activity_focus("app.b"))
        self.assertEqual(("com.android.systemui", "default", 120),
                         (model.raw, model.desired, model.target_hz))
        self.assertEqual(neutral_count, model.apply_count)
        self.assertEqual("app.a", model.last_business)

        model.window_focus("app.b")
        self.assertEqual(("app.b", 60), (model.desired, model.target_hz))

    def test_permission_and_overlay_keep_window_classification(self) -> None:
        for transient_window in (
            "com.android.permissioncontroller",
            "com.zui.game.service",
            "vendor.example.overlay",
        ):
            model = RefreshModel()
            model.profiles.update({"app.a": 90, "app.b": 60})
            model.focus("app.a")
            model.window_focus(transient_window)
            neutral_count = model.apply_count

            self.assertEqual("metadataOnly", model.activity_focus("app.b"))
            self.assertEqual((transient_window, "default", 120),
                             (model.raw, model.desired, model.target_hz))
            self.assertEqual(neutral_count, model.apply_count)
            self.assertEqual("app.a", model.last_business)

            model.window_focus("app.b")
            self.assertEqual(("app.b", 60), (model.desired, model.target_hz))

    def test_activity_first_and_window_first_have_same_final_result(self) -> None:
        activity_first = RefreshModel()
        activity_first.profiles.update({"app.a": 90, "app.b": 60})
        activity_first.focus("app.a")
        activity_first.activity_focus("app.b")
        self.assertEqual([90], activity_first.apply_history)
        activity_first.window_focus("app.b")

        window_first = RefreshModel()
        window_first.profiles.update({"app.a": 90, "app.b": 60})
        window_first.focus("app.a")
        window_first.window_focus("app.b")
        applied_before_metadata = window_first.apply_count
        self.assertEqual("metadataOnly", window_first.activity_focus("app.b"))
        self.assertEqual(applied_before_metadata, window_first.apply_count)

        self.assertEqual(("app.b", "app.b", 60, [90, 60]),
                         (activity_first.raw, activity_first.last_business,
                          activity_first.target_hz, activity_first.apply_history))
        self.assertEqual((activity_first.raw, activity_first.last_business,
                          activity_first.target_hz, activity_first.apply_history),
                         (window_first.raw, window_first.last_business,
                          window_first.target_hz, window_first.apply_history))

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

    def test_100_app_handoffs_with_empty_gaps_apply_once_per_owner(self) -> None:
        model = RefreshModel()
        model.profiles.update({"app.a": 90, "app.b": 60})
        model.focus("app.a")
        base = model.apply_count
        for _ in range(100):
            model.activity_focus("app.b")
            model.window_focus("")
            model.window_focus("app.b")
            model.activity_focus("app.a")
            model.window_focus("")
            model.window_focus("app.a")
        self.assertEqual(200, model.apply_count - base)
        self.assertEqual(200, model.empty_focus_count)
        self.assertNotIn(120, model.apply_history)

    def test_nonempty_systemui_and_ime_remain_neutral_after_empty_gap(self) -> None:
        model = RefreshModel()
        model.profiles["app.a"] = 90
        model.focus("app.a")
        model.window_focus("")
        model.window_focus("com.android.systemui")
        self.assertEqual(("default", 120), (model.desired, model.applied_hz))
        model.set_ime_visible(True)
        self.assertEqual(("default", 120), (model.desired, model.applied_hz))

    def test_exact_oem_controls_do_not_pollute_business_target(self) -> None:
        for control in ("com.lenovo.screensplit", "com.zui.freeform.sidebar"):
            model = RefreshModel()
            model.profiles["app.a"] = 90
            model.focus("app.a")
            model.window_focus(control)
            self.assertEqual(("app.a", "app.a", "default", 120),
                             (model.current, model.last_business,
                              model.desired, model.applied_hz))
            self.assertEqual("transient_package_not_configurable",
                             model.set_explicit_profile(control, 60))

    def test_business_packages_are_not_caught_by_oem_registry(self) -> None:
        for package_name in (
            "com.zui.launcher", "com.zui.notes", "com.android.calculator2",
            "com.android.settings",
        ):
            self.assertFalse(is_transient(package_name), package_name)

    def test_kill_switch_releases_and_reenables_current_raw(self) -> None:
        model = RefreshModel()
        model.profiles["app.b"] = 90
        model.focus("app.b")
        self.assertEqual("releaseRequested", model.set_disable_mask(2))
        self.assertFalse(model.request_owned or model.vote_owned or model.peak_owned)
        self.assertTrue(model.handoff_pending)
        self.assertEqual(("", 0), (model.applied, model.applied_hz))
        self.assertEqual("applied", model.set_disable_mask(0))
        self.assertFalse(model.handoff_pending)
        self.assertEqual(("app.b", 90), (model.applied, model.applied_hz))

        model.focus("com.android.systemui")
        model.set_disable_mask(1)
        self.assertEqual("applied", model.set_disable_mask(0))
        self.assertEqual(("default", 120), (model.applied, model.applied_hz))

    def test_raw_setprop_requires_process_poke(self) -> None:
        model = RefreshModel()
        model.profiles["app.b"] = 90
        model.focus("app.b")
        self.assertEqual("propertyOnly", model.raw_setprop(2))
        self.assertEqual((2, 0, "app.b", 90),
                         (model.property_disable_mask, model.disable_mask,
                          model.applied, model.applied_hz))
        self.assertEqual("releaseRequested", model.sysprops_poke())
        self.assertEqual((2, "", 0),
                         (model.disable_mask, model.applied, model.applied_hz))

    def test_persisted_kill_state_is_read_at_boot(self) -> None:
        model = RefreshModel(persisted_disable_mask=2)
        self.assertEqual((2, 2),
                         (model.property_disable_mask, model.disable_mask))
        self.assertEqual("disabled", model.focus("app.a"))
        self.assertFalse(model.request_owned or model.vote_owned or model.peak_owned)

    def test_authenticated_binder_path_persists_and_transitions_directly(self) -> None:
        model = RefreshModel()
        model.profiles["app.b"] = 90
        model.focus("app.b")
        self.assertEqual("releaseRequested",
                         model.binder_set_refresh_enabled(False))
        self.assertEqual((2, 2, "", 0),
                         (model.property_disable_mask, model.disable_mask,
                          model.applied, model.applied_hz))
        self.assertEqual("applied", model.binder_set_refresh_enabled(True))
        self.assertEqual((0, 0, "app.b", 90),
                         (model.property_disable_mask, model.disable_mask,
                          model.applied, model.applied_hz))

    def test_rapid_toggle_converges_to_the_final_mask(self) -> None:
        model = RefreshModel()
        model.profiles["app.b"] = 90
        model.focus("app.b")
        for mask in (2, 0, 1, 0):
            model.set_disable_mask(mask)
        self.assertEqual(0, model.disable_mask)
        self.assertEqual(("app.b", 90), (model.applied, model.applied_hz))
        self.assertTrue(model.request_owned and model.vote_owned and model.peak_owned)

        for mask in (2, 0, 3):
            model.set_disable_mask(mask)
        self.assertEqual(3, model.disable_mask)
        self.assertFalse(model.request_owned or model.vote_owned or model.peak_owned)
        self.assertEqual(("", 0), (model.applied, model.applied_hz))

        for mask in (0, 2, 0, 3, 0):
            model.raw_setprop(mask)
        self.assertEqual("applied", model.sysprops_poke())
        self.assertEqual((0, "app.b", 90),
                         (model.disable_mask, model.applied, model.applied_hz))

    def test_stale_property_hint_cannot_override_worker_truth(self) -> None:
        model = RefreshModel()
        model.profiles["app.b"] = 90
        model.focus("app.b")

        self.assertEqual("maskOnly", model.consume_property_hint(2, 0))
        self.assertEqual((0, "app.b", 90),
                         (model.disable_mask, model.applied, model.applied_hz))

        self.assertEqual("releaseRequested", model.consume_property_hint(0, 2))
        self.assertEqual((2, "", 0),
                         (model.disable_mask, model.applied, model.applied_hz))

        self.assertEqual("applied", model.consume_property_hint(2, 0))
        self.assertEqual((0, "app.b", 90),
                         (model.disable_mask, model.applied, model.applied_hz))

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
        cls.final_super_verifier = read("scripts/VerifyZuiControlFinalSuper.ps1")
        cls.flash_verifier = read("scripts/VerifyZuiControlFlashPackage.ps1")
        cls.client = read("app/src/main/java/com/zui/zuicontrol/ZuiControlClient.kt")
        cls.main = read("app/src/main/java/com/zui/zuicontrol/MainActivity.kt")
        cls.tile = read("app/src/main/java/com/zui/zuicontrol/ZuiControlTileService.kt")
        cls.quick = read("app/src/main/java/com/zui/zuicontrol/ZuiControlQuickService.kt")
        cls.system_properties = read("framework_patch/stubs/android/os/SystemProperties.java")
        cls.manager = read(
            "framework_patch/src/framework/android/zui/ZuiControlManager.java"
        )
        cls.kill_rc = read("payload/system/etc/init/zui_refresh_kill_switch.rc")
        cls.property_contexts = read("payload/patches/plat_property_contexts_add.txt")
        cls.sepolicy = read("payload/patches/plat_sepolicy_zui_control.cil")
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
        ime_flow = """    :zui_control_ime_visibility
    const/4 p1, 0x0

    if-eqz v1, :zui_control_ime_dispatch

    iget-object v2, p0, Lcom/android/server/wm/DisplayContent;->mInputMethodWindow:Lcom/android/server/wm/WindowState;

    if-eqz v2, :zui_control_ime_dispatch

    invoke-virtual {v2}, Lcom/android/server/wm/WindowState;->getOwningPackage()Ljava/lang/String;

    move-result-object p1

    :zui_control_ime_dispatch
    iget v2, p0, Lcom/android/server/wm/DisplayContent;->mDisplayId:I

    invoke-static {p1, v1, v2}, Lcom/zui/server/control/ZuiControlHooks;->onImeVisibilityChanged(Ljava/lang/String;ZI)V
"""
        self.assertIn(ime_flow, self.framework_patcher)
        self.assertNotIn(
            "iget-object p1, p0, Lcom/android/server/wm/DisplayContent;"
            "->mInputMethodWindow:Lcom/android/server/wm/WindowState;",
            self.framework_patcher,
        )
        for verifier_guard in (
            "const/4 p1, 0x0",
            "if-eqz v1, :(?<dispatch>[A-Za-z0-9_$]+)",
            "if-eqz v2, :\\k<dispatch>",
            ":\\k<dispatch>",
            "complete guarded flow not found",
        ):
            self.assertIn(verifier_guard, self.final_super_verifier)
        for forbidden in ("Thread.sleep", "Runtime.getRuntime", "ProcessBuilder"):
            self.assertNotIn(forbidden, self.hooks)
        focus_section = self.service[
            self.service.index("public void onFocusedAppChanged"):
            self.service.index("private synchronized void handleFocusedActivity")
        ]
        for forbidden in ("Settings.", "getPackagesForUid", "setDisplayProperties",
                          "postDelayed", "sleep("):
            self.assertNotIn(forbidden, focus_section)

    def test_window_focus_is_stable_physical_authority(self) -> None:
        activity_hook = self.service[
            self.service.index("public void onFocusedAppChanged"):
            self.service.index("public void onFocusedWindowChanged")
        ]
        window_hook = self.service[
            self.service.index("public void onFocusedWindowChanged"):
            self.service.index("public void onImeVisibilityChanged")
        ]
        self.assertIn("final boolean windowAuthority = mLatestWindowFocusSeen", activity_hook)
        self.assertIn("if (!windowAuthority)", activity_hook)
        self.assertIn("handleFocusedActivity(activityFocus, windowAuthority", activity_hook)
        self.assertNotIn("!pkg.equals", window_hook)
        self.assertIn("if (pkg.isEmpty())", window_hook)
        self.assertIn("handleEmptyFocusTransition(activityFocus)", window_hook)
        self.assertLess(window_hook.index("if (pkg.isEmpty())"),
                        window_hook.index("mLatestWindowFocusSeen = true"))
        self.assertLess(window_hook.index("if (pkg.isEmpty())"),
                        window_hook.index("mLatestNonImeFocus = windowFocus"))
        self.assertIn("boolean wasEmpty = mLatestWindowFocusEmpty", window_hook)
        self.assertIn("!firstWindowFocus && !wasEmpty", window_hook)
        self.assertLess(window_hook.index("mLatestFocus = windowFocus"),
                        window_hook.index("mLatestWindowFocusEmpty = false"))
        self.assertIn("return !mLatestWindowFocusEmpty", self.service)
        self.assertIn("error=empty_focus_transition", self.service)
        self.assertIn("&& !mLatestWindowFocusEmpty", self.service)
        self.assertIn("volatile FocusSnapshot mLatestActivityFocus", self.service)
        self.assertIn("volatile FocusSnapshot mLatestNonImeFocus", self.service)

    def test_exact_oem_registry_has_no_broad_vendor_rule(self) -> None:
        self.assertIn('p.equals(SCREEN_SPLIT_CONTROL_PACKAGE)', self.service)
        self.assertIn('p.equals(FREEFORM_SIDEBAR_PACKAGE)', self.service)
        self.assertIn('"com.lenovo.screensplit"', self.service)
        self.assertIn('"com.zui.freeform.sidebar"', self.service)
        for forbidden in ('p.startsWith("com.zui.")', 'FLAG_SYSTEM',
                          'isSystemApp'):
            self.assertNotIn(forbidden, self.service)

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
        self.assertIn("readRefreshDisableMask()", self.service)
        self.assertIn("int disableMask = readRefreshDisableMask();", self.service)
        property_section = self.service[
            self.service.index("private void registerRefreshPropertyObserver"):
            self.service.index("private void registerScreenObserver")
        ]
        for forbidden in ("postDelayed", "sleep(", "Timer", "while ("):
            self.assertNotIn(forbidden, property_section)

    def test_kill_switch_uses_direct_binder_and_edge_only_process_poke(self) -> None:
        transact = self.service[
            self.service.index("protected boolean onTransact"):
            self.service.index("protected synchronized void dump")
        ]
        self.assertLess(transact.index("case TX_SET_MODULE_ENABLED"),
                        transact.index("result = setModuleEnabled"))
        tx10 = transact[transact.index("case TX_SET_MODULE_ENABLED"):
                        transact.index("case TX_EXPORT_LOG")]
        self.assertLess(tx10.index("enforceCommandCallerAllowed()"),
                        tx10.index("setModuleEnabled"))
        module_method = self.service[
            self.service.index("private synchronized String setModuleEnabled"):
            self.service.index("private String setProfileLocked")
        ]
        self.assertIn('if (!"refresh".equals(module))', module_method)
        self.assertIn("SystemProperties.set(PROP_REFRESH_DISABLE", module_method)
        self.assertIn("onRefreshPropertiesChanged(readRefreshDisableMask())", module_method)
        self.assertIn("setModuleEnabled(final String module", self.manager)

        self.assertEqual(2, self.kill_rc.count("1599295570"))
        self.assertIn("on property:persist.zui_control.disable=*", self.kill_rc)
        self.assertIn("on property:persist.zui_control.refresh.disable=*", self.kill_rc)
        self.assertEqual(2, self.kill_rc.count("exec_background u:r:shell:s0 shell shell"))
        self.assertEqual(2, self.kill_rc.count('/system/bin/sh -c "exec /system/bin/service call'))
        self.assertNotIn("-- /system/bin/service call", self.kill_rc)
        self.assertIn("Assert-SmaliDispatchAuthorized", self.flash_verifier)
        self.assertIn("'TX10'", self.flash_verifier)
        self.assertIn("'TX12'", self.flash_verifier)
        for forbidden in ("service zui_", "postDelayed", "while ", "sleep "):
            self.assertNotIn(forbidden, self.kill_rc)

        for context in (
            "persist.zui_control.disable u:object_r:"
            "zui_control_refresh_disable_prop:s0 exact bool",
            "persist.zui_control.refresh.disable u:object_r:"
            "zui_control_refresh_disable_prop:s0 exact bool",
        ):
            self.assertIn(context, self.property_contexts)
        for rule in (
            "(type zui_control_refresh_disable_prop)",
            "(typeattributeset system_restricted_property_type "
            "(zui_control_refresh_disable_prop))",
            "(allow system_server zui_control_refresh_disable_prop "
            "(property_service (set)))",
            "(allow shell zui_control_refresh_disable_prop "
            "(property_service (set)))",
        ):
            self.assertIn(rule, self.sepolicy)
        self.assertNotIn("(allow system_server shell_prop", self.sepolicy)
        for domain in ("priv_app", "untrusted_app"):
            self.assertNotIn(
                f"(allow {domain} zui_control_refresh_disable_prop "
                "(property_service (set)))",
                self.sepolicy,
            )

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
        self.assertIn("mLatestNonImeFocus", self.service)
        self.assertIn("mNonImeFocusedPackage", self.service)
        self.assertIn("displayId != Display.DEFAULT_DISPLAY", self.service)
        self.assertIn("private boolean saveProfiles()", self.service)
        self.assertIn('return "ok=0\\nerror=" + mLastError;', self.service)
        self.assertIn("profileSaved=1", self.service)
        self.assertIn("userId: Int = currentUserId()", self.client)
        self.assertIn("userId == ZuiControlClient.currentUserId()", self.main)


if __name__ == "__main__":
    unittest.main(verbosity=2)
