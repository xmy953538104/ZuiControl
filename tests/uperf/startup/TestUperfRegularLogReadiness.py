#!/usr/bin/env python3
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest


REPO = Path(__file__).resolve().parents[3]
SOURCE = REPO / "native/zui_uperf_supervisor.c"
WRAPPER = REPO / "payload/system/bin/zui_uperf_service"
DUMMY = REPO / "tests/fixtures/dummy_uperf.py"
FINAL_VERIFIER = REPO / "scripts/build/VerifyZuiControlFlashPackage.ps1"
RUNTIME_VERIFIER = REPO / "tests/selinux/TestUperfRuntimeAccess.py"


def wait_for(predicate, timeout: float = 5.0, label: str = "condition"):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(0.02)
    raise AssertionError(f"timeout waiting for {label}")


def read_pid(path: Path) -> int | None:
    try:
        return int(path.read_text(encoding="ascii").strip())
    except (FileNotFoundError, ValueError):
        return None


def terminate_pid(pid: int | None) -> None:
    if pid is None:
        return
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        pass


class SupervisedRun:
    def __init__(
        self,
        binary: Path,
        root: Path,
        mode: str,
        executable: Path = DUMMY,
        timeout_ms: int = 2000,
    ) -> None:
        self.root = root
        self.state = root / "state"
        self.log = root / "logs/uperf.log"
        self.ready = root / "runtime/.service_ready_uptime"
        self.config = root / "config.json"
        self.log.parent.mkdir(parents=True)
        self.ready.parent.mkdir(parents=True)
        self.config.write_text(
            json.dumps({"mode": mode, "state_dir": str(self.state)}), encoding="utf-8"
        )
        env = os.environ.copy()
        env.update(
            {
                "ZUI_UPERF_TEST_BINARY": str(executable),
                "ZUI_UPERF_TEST_TIMEOUT_MS": str(timeout_ms),
                "ZUI_UPERF_TEST_CADENCE_MS": "20",
            }
        )
        self.stderr_path = root / "supervisor.stderr"
        stderr = self.stderr_path.open("wb")
        try:
            self.process = subprocess.Popen(
                [str(binary), str(self.config), str(self.log), str(self.ready)],
                env=env,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=stderr,
            )
        finally:
            stderr.close()

    @property
    def daemon_pid(self) -> int | None:
        return read_pid(self.state / "daemon.pid")

    @property
    def worker_pid(self) -> int | None:
        return read_pid(self.state / "worker.pid")

    def wait_ready(self) -> Path:
        return wait_for(self.ready.is_file, label="atomic ready marker")

    def stderr(self) -> str:
        return self.stderr_path.read_text(encoding="utf-8", errors="replace")

    def cleanup(self) -> None:
        terminate_pid(self.daemon_pid)
        terminate_pid(self.worker_pid)
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=2)


class RegularLogReadinessTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        if sys.platform != "linux":
            raise unittest.SkipTest("subreaper fixtures require Linux")
        compiler = shutil.which("cc")
        if compiler is None:
            raise unittest.SkipTest("cc is required")
        cls.build_root = Path(tempfile.mkdtemp(prefix="zui-regular-log-host-"))
        cls.binary = cls.build_root / "zui_uperf_supervisor"
        DUMMY.chmod(0o755)
        subprocess.run(
            [
                compiler,
                "-std=c11",
                "-O2",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-DZUI_SUPERVISOR_TESTING=1",
                str(SOURCE),
                "-o",
                str(cls.binary),
            ],
            check=True,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        shutil.rmtree(cls.build_root, ignore_errors=True)

    def test_a_absent_path_becomes_regular_and_ready(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run = SupervisedRun(self.binary, Path(temporary), "create_ready")
            try:
                self.assertFalse(run.log.exists())
                run.wait_ready()
                self.assertTrue(stat.S_ISREG(run.log.stat().st_mode))
                self.assertRegex(run.ready.read_text(encoding="ascii"), r"^\d+\n$")
                self.assertEqual(stat.S_IMODE(run.ready.stat().st_mode), 0o600)
                self.assertFalse(Path(str(run.ready) + ".tmp").exists())
                self.assertIsNone(run.process.poll())
            finally:
                run.cleanup()

    def test_b_replaced_path_new_inode_is_followed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run = SupervisedRun(self.binary, Path(temporary), "replace_ready")
            try:
                run.wait_ready()
                first = int((run.state / "first_inode").read_text(encoding="ascii"))
                final = int((run.state / "final_inode").read_text(encoding="ascii"))
                self.assertNotEqual(first, final)
                self.assertEqual(run.log.stat().st_ino, final)
            finally:
                run.cleanup()

    def test_c_truncation_resets_offset_and_partial_record(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run = SupervisedRun(self.binary, Path(temporary), "truncate_ready")
            try:
                run.wait_ready()
                self.assertTrue((run.state / "truncated").is_file())
            finally:
                run.cleanup()

    def test_d_ready_split_across_writes_is_retained(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run = SupervisedRun(self.binary, Path(temporary), "split_ready")
            try:
                wait_for((run.state / "partial_written").is_file, label="partial ready record")
                self.assertFalse(run.ready.exists())
                run.wait_ready()
            finally:
                run.cleanup()

    def test_e_no_ready_hits_accelerated_deadline(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            start = time.monotonic()
            run = SupervisedRun(
                self.binary, Path(temporary), "no_ready", timeout_ms=600
            )
            try:
                self.assertEqual(run.process.wait(timeout=3), 1)
                elapsed = time.monotonic() - start
                self.assertGreaterEqual(elapsed, 0.50)
                self.assertLess(elapsed, 2.0)
                self.assertFalse(run.ready.exists())
                self.assertIn("readiness timed out", run.stderr())
            finally:
                run.cleanup()

    def test_f_explicit_failure_is_fail_fast(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            start = time.monotonic()
            run = SupervisedRun(self.binary, Path(temporary), "failed")
            try:
                self.assertEqual(run.process.wait(timeout=2), 1)
                self.assertLess(time.monotonic() - start, 1.5)
                self.assertFalse(run.ready.exists())
                self.assertIn("reported startup failure", run.stderr())
            finally:
                run.cleanup()

    def test_g_no_log_fd_or_polling_after_ready(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run = SupervisedRun(self.binary, Path(temporary), "create_ready")
            try:
                run.wait_ready()
                fd_targets = [
                    os.readlink(entry)
                    for entry in Path(f"/proc/{run.process.pid}/fd").iterdir()
                ]
                self.assertFalse(any(str(run.log) in target for target in fd_targets))
                run.log.unlink()
                os.mkfifo(run.log, 0o600)
                time.sleep(0.35)
                self.assertIsNone(run.process.poll())
            finally:
                run.cleanup()

    def test_h_ready_live_daemon_blocks_in_waitpid(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run = SupervisedRun(self.binary, Path(temporary), "create_ready")
            try:
                run.wait_ready()
                self.assertIsNotNone(wait_for(lambda: run.daemon_pid, label="daemon pid"))
                os.kill(run.process.pid, signal.SIGUSR1)
                time.sleep(0.30)
                self.assertIsNone(run.process.poll())
            finally:
                run.cleanup()

    def test_i_real_tree_death_exits_supervisor(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run = SupervisedRun(self.binary, Path(temporary), "create_ready")
            try:
                run.wait_ready()
                daemon = wait_for(lambda: run.daemon_pid, label="daemon pid")
                terminate_pid(daemon)
                self.assertEqual(run.process.wait(timeout=5), 1)
            finally:
                run.cleanup()

    def test_j_multiple_descendants_wait_for_last(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run = SupervisedRun(self.binary, Path(temporary), "multiple")
            try:
                run.wait_ready()
                daemon = wait_for(lambda: run.daemon_pid, label="daemon pid")
                worker = wait_for(lambda: run.worker_pid, label="worker pid")
                terminate_pid(daemon)
                wait_for(lambda: not Path(f"/proc/{daemon}").exists(), label="daemon reap")
                self.assertIsNone(run.process.poll())
                terminate_pid(worker)
                self.assertEqual(run.process.wait(timeout=5), 1)
            finally:
                run.cleanup()

    def test_k_production_contract_has_no_fifo_or_steady_shell(self) -> None:
        source = SOURCE.read_text(encoding="utf-8")
        wrapper = WRAPPER.read_text(encoding="utf-8")
        final_verifier = FINAL_VERIFIER.read_text(encoding="utf-8")
        runtime_verifier = RUNTIME_VERIFIER.read_text(encoding="utf-8")
        combined_production = source + "\n" + wrapper
        self.assertIn("STARTUP_TIMEOUT_MS 20000L", source)
        self.assertIn("STARTUP_CADENCE_MS 100L", source)
        self.assertIn("clock_nanosleep", source)
        self.assertIn("waitpid(-1, &status, 0)", source)
        self.assertIn('execl(binary, binary, config, "-o", log_path', source)
        self.assertIn('exec "$SUPERVISOR" "$CONFIG" "$LOG" "$READY_UPTIME"', wrapper)
        self.assertIn("atomic ready marker", final_verifier)
        self.assertIn("regular Uperf startup log", runtime_verifier)
        for token in (
            "mkfifo",
            ".service_log_pipe",
            "drain_uperf_log",
            "sleep 5",
            'wait "$supervisor_pid"',
        ):
            self.assertNotIn(token, combined_production)
        for tool in ("ps", "grep"):
            self.assertNotRegex(wrapper, rf"(?m)(?:^|[;&|]\s*){tool}\s")
            self.assertNotRegex(source, rf'execlp?\([^;\n]*"{tool}"')
        self.assertNotRegex(wrapper, r"(?m)^.*&\s*$")

    def test_l_wrapper_exec_removes_steady_shell_process(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            state = root / "state"
            config = root / "config.json"
            log = root / "logs/uperf.log"
            ready = root / "runtime/.service_ready_uptime"
            config.write_text(
                json.dumps({"mode": "create_ready", "state_dir": str(state)}),
                encoding="utf-8",
            )
            log.parent.mkdir()
            ready.parent.mkdir()
            script = WRAPPER.read_text(encoding="utf-8")
            replacements = {
                "CONFIG=/data/vendor/zui_control/uperf/uperf.json": f"CONFIG={config}",
                "LOG=/data/vendor/zui_control/log/uperf.log": f"LOG={log}",
                "READY_UPTIME=/data/vendor/zui_control/uperf/.service_ready_uptime": f"READY_UPTIME={ready}",
                "SUPERVISOR=/system/bin/zui_uperf_supervisor": f"SUPERVISOR={self.binary}",
            }
            for old, new in replacements.items():
                self.assertIn(old, script)
                script = script.replace(old, new)
            wrapper = root / "wrapper.sh"
            wrapper.write_text(script, encoding="utf-8")
            wrapper.chmod(0o755)
            env = os.environ.copy()
            env.update(
                {
                    "ZUI_UPERF_TEST_BINARY": str(DUMMY),
                    "ZUI_UPERF_TEST_TIMEOUT_MS": "2000",
                    "ZUI_UPERF_TEST_CADENCE_MS": "20",
                }
            )
            process = subprocess.Popen(
                ["bash", str(wrapper)],
                env=env,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            daemon = None
            try:
                wait_for(ready.is_file, label="wrapper-path ready marker")
                daemon = wait_for(lambda: read_pid(state / "daemon.pid"), label="daemon pid")
                self.assertEqual(
                    Path(f"/proc/{process.pid}/exe").resolve(), self.binary.resolve()
                )
                terminate_pid(daemon)
                self.assertEqual(process.wait(timeout=5), 1)
            finally:
                terminate_pid(daemon)
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=2)


def main() -> int:
    receipt_dir = None
    if "--receipt-dir" in sys.argv:
        index = sys.argv.index("--receipt-dir")
        receipt_dir = Path(sys.argv[index + 1])
        del sys.argv[index:index + 2]
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(RegularLogReadinessTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if receipt_dir is not None:
        receipt_dir.mkdir(parents=True, exist_ok=True)
        statuses = {letter: "PASS" if result.wasSuccessful() else "FAIL" for letter in "ABCDEFGHIJK"}
        summary = {
            "tests_run": result.testsRun,
            "failures": len(result.failures),
            "errors": len(result.errors),
            "skipped": len(result.skipped),
            "contract_A_to_K": statuses,
            "regular_log_readiness_fixture": "PASS" if result.wasSuccessful() else "FAIL",
            "subreaper_fixture": "PASS" if result.wasSuccessful() else "FAIL",
            "steady_shell_process": "ABSENT" if result.wasSuccessful() else "UNPROVEN",
            "result": "PASS" if result.wasSuccessful() else "FAIL",
        }
        (receipt_dir / "host_fixture_results.json").write_text(
            json.dumps(summary, indent=2) + "\n", encoding="utf-8"
        )
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
