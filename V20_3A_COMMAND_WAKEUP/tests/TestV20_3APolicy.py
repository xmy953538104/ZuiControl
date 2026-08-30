#!/usr/bin/env python3
import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


def read(relative):
    return (ROOT / relative).read_text(encoding="utf-8")


class V203ACommandWakeupPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.daemon = read("payload/system/bin/zui_controld")
        cls.rc = read("payload/system/etc/init/zui_controld.rc")
        cls.property_contexts = read("payload/patches/plat_property_contexts_add.txt")
        cls.policy = read("payload/patches/plat_sepolicy_zui_control.cil")
        cls.service = read(
            "framework_patch/src/services/com/zui/server/control/ZuiControlService.java"
        )
        cls.manager = read(
            "framework_patch/src/framework/android/zui/ZuiControlManager.java"
        )
        cls.stub = read(
            "framework-stubs/src/main/java/android/zui/ZuiControlManager.java"
        )
        cls.client = read(
            "app/src/main/java/com/zui/zuicontrol/ZuiControlClient.kt"
        )
        cls.request = read(
            "app/src/main/java/com/zui/zuicontrol/ZuiControlRequest.kt"
        )
        cls.activity = read(
            "app/src/main/java/com/zui/zuicontrol/MainActivity.kt"
        )
        cls.boot_receiver = read(
            "app/src/main/java/com/zui/zuicontrol/BootReceiver.kt"
        )

    def function_body(self, source, name):
        match = re.search(rf"(?ms)^{re.escape(name)}\(\) \{{.*?^\}}", source)
        self.assertIsNotNone(match, name)
        return match.group(0)

    def test_binder_transaction_is_synchronized_everywhere(self):
        self.assertIn("TX_NOTIFY_CONTROL_REQUEST = 12", self.service)
        self.assertIn("TX_NOTIFY_CONTROL_REQUEST = 12", self.manager)
        self.assertIn(
            "notifyControlRequest(String requestId, String requestSha256)", self.stub
        )
        self.assertIn(
            "it.notifyControlRequest(requestId, requestSha256)", self.client
        )
        self.assertIn(
            "code >= 1 && code <= TX_NOTIFY_CONTROL_REQUEST", self.service
        )

    def test_command_binder_has_no_system_uid_bypass(self):
        command_case = re.search(
            r"(?s)case TX_NOTIFY_CONTROL_REQUEST:.*?break;", self.service
        ).group(0)
        self.assertIn("enforceCommandCallerAllowed();", command_case)
        strict = re.search(
            r"(?s)private void enforceCommandCallerAllowed\(\).*?^    \}",
            self.service,
            re.MULTILINE,
        ).group(0)
        self.assertIn("enforceZuiControlCaller(Binder.getCallingUid())", strict)
        self.assertNotIn("SYSTEM_UID", strict)
        self.assertIn("Arrays.asList(packages).contains(APP_PACKAGE)", self.service)
        self.assertIn("hex(RELEASE_CERT)", self.service)
        self.assertIn("ApplicationInfo.FLAG_DEBUGGABLE", self.service)

    def test_binder_binds_current_payload_then_rings_doorbell(self):
        method = re.search(
            r"(?s)private synchronized String notifyControlRequest\(.*?^    \}",
            self.service,
            re.MULTILINE,
        ).group(0)
        self.assertIn("validRequestId", method)
        self.assertIn("validSha256", method)
        self.assertEqual(1, method.count("Settings.System.getString"))
        self.assertIn("request_payload_mismatch", method)
        self.assertLess(
            method.index("SystemProperties.set(PROP_COMMAND_ID, id)"),
            method.index("SystemProperties.set(PROP_COMMAND_SEQ, token)"),
        )
        self.assertLess(
            method.index("SystemProperties.set(PROP_COMMAND_SHA256, sha256)"),
            method.index("SystemProperties.set(PROP_COMMAND_SEQ, token)"),
        )
        self.assertIn("SystemProperties.set(PROP_COMMAND_SEQ, token)", method)
        for forbidden in (
            "Settings.System.put",
            "Runtime",
            "ProcessBuilder",
            "Thread.sleep",
            "set_uperf_mode",
        ):
            self.assertNotIn(forbidden, method)

    def test_property_has_one_writer_and_exact_context(self):
        self.assertIn(
            "sys.zui_control.command_seq "
            "u:object_r:zui_control_command_seq_prop:s0 exact string",
            self.property_contexts,
        )
        self.assertIn(
            "(allow system_server zui_control_command_seq_prop "
            "(property_service (set)))",
            self.policy,
        )
        for name in ("command_id", "command_sha256"):
            self.assertIn(
                f"sys.zui_control.{name} "
                "u:object_r:zui_control_command_auth_prop:s0 exact string",
                self.property_contexts,
            )
        self.assertIn(
            "(allow system_server zui_control_command_auth_prop "
            "(property_service (set)))",
            self.policy,
        )
        for domain in ("shell", "priv_app", "untrusted_app"):
            self.assertNotIn(
                f"(allow {domain} zui_control_command_seq_prop "
                "(property_service (set)))",
                self.policy,
            )
            self.assertNotIn(
                f"(allow {domain} zui_control_command_auth_prop "
                "(property_service (set)))",
                self.policy,
            )

    def test_init_uses_disabled_oneshot_service(self):
        self.assertEqual(
            1, self.rc.count("on property:sys.zui_control.command_seq=*")
        )
        self.assertEqual(1, self.rc.count("    start zui_control_request"))
        service = re.search(
            r"(?ms)^service zui_control_request .*?(?=^service |\Z)", self.rc
        ).group(0)
        self.assertIn("--oneshot-request", service)
        self.assertIn("${sys.zui_control.command_id:-unset}", service)
        self.assertIn("${sys.zui_control.command_sha256:-unset}", service)
        self.assertIn("\n    disabled\n", service)
        self.assertIn("\n    oneshot\n", service)
        self.assertIn("\n    user root\n", service)
        self.assertIn("\n    group root system shell readproc\n", service)
        self.assertIn("\n    seclabel u:r:shell:s0\n", service)
        self.assertIn(
            "mkdir /data/vendor/zui_control/zuicontrol 0700 root root", self.rc
        )
        self.assertIn("mkdir /data/vendor/zui_control 0755 root root", self.rc)
        self.assertIn('chmod 0755 "$DATA_ROOT"', self.daemon)

    def test_persistent_daemon_has_no_request_poll(self):
        main = self.function_body(self.daemon, "main_loop")
        self.assertNotIn("process_settings_request", main)
        self.assertNotIn("sleep 1", main)
        self.assertIn("sleep 20", main)

    def test_oneshot_reuses_transaction_state_once(self):
        oneshot = self.function_body(self.daemon, "oneshot_request")
        self.assertIn("ensure_request_dirs", oneshot)
        self.assertIn('id -u 2>/dev/null', oneshot)
        self.assertIn('captured_request="$(settings_get_clean "$REQ_TEXT_KEY")"', oneshot)
        self.assertIn('captured_sha256="$(request_sha256 "$captured_request")"', oneshot)
        self.assertLess(
            oneshot.index('captured_sha256="$(request_sha256 "$captured_request")"'),
            oneshot.index('init_request_state "$captured_request"'),
        )
        self.assertIn("init_request_state", oneshot)
        self.assertEqual(1, oneshot.count("process_settings_request"))
        self.assertNotIn("while", oneshot)
        self.assertNotIn("sleep", oneshot)
        self.assertNotIn("ensure_scheduler_running", oneshot)

    def test_claim_precedes_action_and_terminal_receipt_precedes_ack(self):
        process = re.search(
            r"(?ms)^process_settings_request\(\) \{.*?^\}", self.daemon
        ).group(0)
        self.assertLess(
            process.index('persist_request_claim "$request"'),
            process.index('handle_command "$cmd"'),
        )
        finish = re.search(r"(?ms)^finish_request\(\) \{.*?^\}", self.daemon).group(0)
        self.assertLess(
            finish.index('persist_completion "$request"'),
            finish.index('clear_request_claim "$request"'),
        )
        self.assertLess(
            finish.index('clear_request_claim "$request"'),
            finish.index("publish_pending_terminal_ack"),
        )
        self.assertIn("indeterminate_after_claim", self.daemon)
        self.assertIn('atomic_write_text "$ACTIVE_REQUEST_CLAIM" "$1" 0600', self.daemon)
        self.assertIn('$terminal_ack" 0600 || return 1', self.daemon)

    def test_app_write_kick_poll_and_rekick_are_scoped(self):
        send = re.search(
            r"(?s)fun send\(.*?^    \}", self.request, re.MULTILINE
        ).group(0)
        self.assertLess(
            send.index("savePending(context, pending)"),
            send.index("Settings.System.putString"),
        )
        self.assertLess(
            send.index("Settings.System.putString"),
            send.index("ZuiControlClient.notifyControlRequest(requestId, pending.sha256)"),
        )
        self.assertIn("createDeviceProtectedStorageContext", self.request)
        self.assertIn("sha256(text) == digest", self.request)
        self.assertIn("ACK_POLL_MS = 200L", self.request)
        self.assertIn("INITIAL_KICK_RETRY_MS = 1_000L", self.request)
        self.assertIn("MAX_KICK_RETRIES = 6", self.request)
        self.assertIn("Thread { ZuiControlRequest.kickPending(this) }.start()", self.activity)
        self.assertIn("ZuiControlRequest.kickPending(context)", self.boot_receiver)

    def test_refresh_and_uperf_owners_are_unchanged(self):
        self.assertIn("mUperfScenePolicy.onSystemStateChanged", self.service)
        self.assertIn("SystemProperties.set(PROP_UPERF_MODE, mDesiredMode)", self.service)
        self.assertIn("refreshOwner=system", self.service)
        self.assertNotIn("kgsl", self.service.lower())
        self.assertNotIn("thermal", self.daemon.lower())


if __name__ == "__main__":
    unittest.main(verbosity=2)
