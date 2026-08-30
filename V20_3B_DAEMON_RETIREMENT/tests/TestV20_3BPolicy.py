#!/usr/bin/env python3
"""Host-only architecture checks for the V20.3B daemon-retirement candidate."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def section(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


class V20_3BPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.daemon = read("payload/system/bin/zui_controld")
        cls.control_rc = read("payload/system/etc/init/zui_controld.rc")
        cls.scheduler_rc = read("payload/system/etc/init/zui_scheduler.rc")
        cls.property_contexts = read("payload/patches/plat_property_contexts_add.txt")
        cls.sepolicy = read("payload/patches/plat_sepolicy_zui_control.cil")
        cls.service = read(
            "framework_patch/src/services/com/zui/server/control/ZuiControlService.java"
        )
        cls.request = read("app/src/main/java/com/zui/zuicontrol/ZuiControlRequest.kt")
        cls.activity = read("app/src/main/java/com/zui/zuicontrol/MainActivity.kt")

    def test_health_is_on_demand_property_read_only(self) -> None:
        health = section(
            self.service,
            "private String schedulerHealthStateLines()",
            "private static boolean isUperfMode",
        )
        for prop in (
            "PROP_SCHEDULER_ACTIVE",
            "PROP_UPERF_SERVICE",
            "PROP_UPERF_MODE",
            "PROP_ASOUL_SERVICE",
        ):
            self.assertIn(f"SystemProperties.get({prop}", health)
        for field in (
            "schedulerActive",
            "uperfServiceState",
            "uperfMode",
            "asoulServiceState",
            "schedulerHealth",
            "lastSchedulerError",
        ):
            self.assertIn(f"\\n{field}=", health)
        for forbidden in (
            "Runtime.getRuntime",
            "ProcessBuilder",
            "Thread.sleep",
            "Settings.",
            "/system/bin/sh",
            "pidof",
        ):
            self.assertNotIn(forbidden, health)
        self.assertEqual(2, self.service.count("schedulerHealthStateLines("))
        self.assertIn(
            '(observeSchedulerHealth ? schedulerHealthStateLines() : "")',
            self.service,
        )
        self.assertIn('"zui_control_status_text", state(false)', self.service)
        self.assertNotIn("PROP_ASOUL_DESIRED", self.service)
        self.assertNotIn("asoulDesiredState", self.service)
        self.assertNotIn("PROP_VENDOR_PERFSERVICE", self.service)
        self.assertNotIn("vendorPerfserviceState", self.service)
        self.assertNotIn("main_loop()", self.daemon)
        self.assertNotIn("publish_scheduler_health", self.daemon)
        self.assertNotIn("sleep 20", self.daemon)

    def test_dp_pending_recovers_to_terminal_in_one_launch_session(self) -> None:
        recover = section(
            self.request,
            "fun recoverPending(context: Context): Ack?",
            "fun awaitTerminalAck(",
        )
        self.assertRegex(
            recover,
            r"val requestId = kickPending\(context\) \?: return null\s+"
            r"return awaitTerminalAck\(context, requestId\)",
        )
        self.assertIn(
            "Thread { runCatching { ZuiControlRequest.recoverPending(appContext) } }.start()",
            self.activity,
        )
        self.assertNotIn("ZuiControlRequest.kickPending(", self.activity)

        await_ack = section(self.request, "fun awaitTerminalAck(", "internal fun buildRequestText(")
        self.assertIn("ack?.requestId == requestId && ack.command == requestCommand", await_ack)
        self.assertIn("if (ack.isTerminal)", await_ack)
        self.assertIn("clearPending(context, requestId)", await_ack)

    def test_scheduler_owner_property_and_selinux_are_exact(self) -> None:
        self.assertIn(
            "sys.zui_control.scheduler_active "
            "u:object_r:zui_control_scheduler_active_prop:s0 exact enum 0 1",
            self.property_contexts.splitlines(),
        )
        self.assertIn(
            "(allow init zui_control_scheduler_active_prop (property_service (set)))",
            self.sepolicy.splitlines(),
        )
        self.assertIn(
            "(allow system_server zui_control_scheduler_active_prop "
            "(file (getattr map open read)))",
            self.sepolicy.splitlines(),
        )
        self.assertIsNone(
            re.search(
                r"\(allow (?:system_server|shell|priv_app|untrusted_app) "
                r"zui_control_scheduler_active_prop \(property_service \(set\)\)\)",
                self.sepolicy,
            )
        )
        conditional = (
            "on property:init.svc.vendor.perfservice=running && "
            "property:sys.zui_control.scheduler_active=1"
        )
        self.assertEqual(1, self.scheduler_rc.count(conditional))
        vendor_triggers = [
            line
            for line in self.scheduler_rc.splitlines()
            if line.startswith("on property:init.svc.vendor.perfservice=running")
        ]
        self.assertEqual([conditional], vendor_triggers)
        self.assertEqual(
            1,
            self.scheduler_rc.count(
                "on property:zui_control.asoul=start && "
                "property:sys.zui_control.scheduler_active=1"
            ),
        )

    def test_app_reads_binder_state_not_retired_health_setting(self) -> None:
        self.assertIn("val state = ZuiControlClient.stateText()", self.activity)
        for field in (
            "schedulerActive",
            "uperfServiceState",
            "uperfMode",
            "asoulServiceState",
            "schedulerHealth",
        ):
            self.assertIn(f'ZuiControlClient.stateValue(state, "{field}")', self.activity)
        for source in (ROOT / "app/src/main/java").rglob("*.kt"):
            if source.name != "ZuiControlContract.kt":
                self.assertNotIn("KEY_UPERF_HEALTH", source.read_text(encoding="utf-8"))

    def test_only_authenticated_oneshot_request_service_remains(self) -> None:
        service = (
            "service zui_control_request /system/bin/sh /system/bin/zui_controld "
            "--oneshot-request ${sys.zui_control.command_id:-unset} "
            "${sys.zui_control.command_sha256:-unset}"
        )
        self.assertIn(service, self.control_rc.splitlines())
        self.assertEqual(1, self.control_rc.count("    start zui_control_request"))
        self.assertIn("    disabled", self.control_rc)
        self.assertIn("    oneshot", self.control_rc)
        self.assertIsNone(re.search(r"^service\s+zui_controld(?:\s|$)", self.control_rc, re.M))
        self.assertIsNone(re.search(r"^\s+start\s+zui_controld(?:\s|$)", self.control_rc, re.M))
        self.assertIn(
            '--oneshot-request) shift; oneshot_request "${1:-}" "${2:-}" ;;',
            self.daemon,
        )
        self.assertIn('") exit 2 ;;', self.daemon)


if __name__ == "__main__":
    unittest.main(verbosity=2)
