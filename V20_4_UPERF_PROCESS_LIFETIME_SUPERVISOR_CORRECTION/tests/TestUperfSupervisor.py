#!/usr/bin/env python3
from __future__ import annotations

import json
import os
from pathlib import Path
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest


REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / "native/zui_uperf_supervisor.c"
WRAPPER = REPO / "payload/system/bin/zui_uperf_service"
DUMMY = Path(__file__).with_name("dummy_uperf.py")
CONTEXTS = REPO / "payload/patches/plat_file_contexts_add.txt"


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
    def __init__(self, binary: Path, root: Path, mode: str, executable: Path = DUMMY):
        self.root = root
        self.state = root / "state"
        self.fifo = root / "uperf.pipe"
        self.config = root / "config.json"
        self.config.write_text(
            json.dumps({"mode": mode, "state_dir": str(self.state)}), encoding="utf-8"
        )
        os.mkfifo(self.fifo, 0o600)
        self.dummy_fd = os.open(self.fifo, os.O_RDWR | os.O_NONBLOCK)
        self.reader_fd = os.open(self.fifo, os.O_RDONLY | os.O_NONBLOCK)
        env = os.environ.copy()
        env["ZUI_UPERF_TEST_BINARY"] = str(executable)
        self.process = subprocess.Popen(
            [str(binary), str(self.config), str(self.fifo)],
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.buffer = b""

    @property
    def daemon_pid(self) -> int | None:
        return read_pid(self.state / "daemon.pid")

    @property
    def worker_pid(self) -> int | None:
        return read_pid(self.state / "worker.pid")

    def read_line(self, timeout: float = 5.0) -> str:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if b"\n" in self.buffer:
                raw, self.buffer = self.buffer.split(b"\n", 1)
                return raw.decode("utf-8", errors="replace")
            readable, _, _ = select.select([self.reader_fd], [], [], 0.1)
            if not readable:
                continue
            chunk = os.read(self.reader_fd, 4096)
            if chunk:
                self.buffer += chunk
            elif self.process.poll() is not None:
                break
        raise AssertionError("timeout waiting for FIFO line")

    def close_dummy(self) -> None:
        if self.dummy_fd >= 0:
            os.close(self.dummy_fd)
            self.dummy_fd = -1

    def wait_eof(self, timeout: float = 5.0) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            readable, _, _ = select.select([self.reader_fd], [], [], 0.1)
            if readable and os.read(self.reader_fd, 4096) == b"":
                return
        raise AssertionError("FIFO EOF was not observed")

    def cleanup(self) -> None:
        self.close_dummy()
        terminate_pid(self.daemon_pid)
        terminate_pid(self.worker_pid)
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=2)
        if self.reader_fd >= 0:
            os.close(self.reader_fd)
            self.reader_fd = -1


class UperfSupervisorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        if sys.platform != "linux":
            raise unittest.SkipTest("process-lifetime fixtures require Linux fork/subreaper semantics")
        compiler = shutil.which("cc")
        if compiler is None:
            raise unittest.SkipTest("cc is required")
        cls.build_root = Path(tempfile.mkdtemp(prefix="zui-supervisor-host-"))
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

    def test_a_fifo_eof_is_not_process_lifetime(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run = SupervisedRun(self.binary, Path(temporary), "single")
            try:
                self.assertTrue(run.read_line().endswith(" I Uperf is running"))
                wait_for(lambda: (run.state / "writer_closed").is_file(), label="writer close")
                run.close_dummy()
                run.wait_eof()
                self.assertIsNone(run.process.poll(), "old FIFO-only lifetime assumption unexpectedly held")
                self.assertIsNotNone(wait_for(lambda: run.daemon_pid, label="daemon pid"))
            finally:
                run.cleanup()

    def test_b_real_tree_death_exits_supervisor(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run = SupervisedRun(self.binary, Path(temporary), "single")
            try:
                self.assertTrue(run.read_line().endswith(" I Uperf is running"))
                daemon = wait_for(lambda: run.daemon_pid, label="daemon pid")
                run.close_dummy()
                terminate_pid(daemon)
                self.assertEqual(run.process.wait(timeout=5), 1)
            finally:
                run.cleanup()

    def test_c_multiple_descendants_wait_for_last(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run = SupervisedRun(self.binary, Path(temporary), "multiple")
            try:
                self.assertTrue(run.read_line().endswith(" I Uperf is running"))
                daemon = wait_for(lambda: run.daemon_pid, label="daemon pid")
                worker = wait_for(lambda: run.worker_pid, label="worker pid")
                run.close_dummy()
                terminate_pid(daemon)
                wait_for(lambda: not Path(f"/proc/{daemon}").exists(), label="first descendant death")
                self.assertIsNone(run.process.poll())
                terminate_pid(worker)
                self.assertEqual(run.process.wait(timeout=5), 1)
            finally:
                run.cleanup()

    def test_d_exec_failure_is_fail_fast(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            missing = Path(temporary) / "missing-uperf"
            run = SupervisedRun(self.binary, Path(temporary), "single", missing)
            try:
                line = run.read_line(timeout=2)
                self.assertTrue(line.startswith("ZUI_UPERF_SUPERVISOR_EXEC_FAILED"), line)
                run.close_dummy()
                self.assertEqual(run.process.wait(timeout=2), 127)
            finally:
                run.cleanup()

    def test_e_eintr_and_zombie_reaping(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run = SupervisedRun(self.binary, Path(temporary), "multiple")
            try:
                self.assertTrue(run.read_line().endswith(" I Uperf is running"))
                daemon = wait_for(lambda: run.daemon_pid, label="daemon pid")
                worker = wait_for(lambda: run.worker_pid, label="worker pid")
                run.close_dummy()
                os.kill(run.process.pid, signal.SIGUSR1)
                self.assertIsNone(run.process.poll())
                terminate_pid(daemon)
                wait_for(lambda: not Path(f"/proc/{daemon}").exists(), label="daemon reap")

                def no_zombies() -> bool:
                    output = subprocess.run(
                        ["ps", "--ppid", str(run.process.pid), "-o", "stat="],
                        check=True,
                        capture_output=True,
                        text=True,
                    ).stdout.splitlines()
                    return all(not value.strip().startswith("Z") for value in output)

                wait_for(no_zombies, label="zombie reap")
                self.assertIsNone(run.process.poll())
                terminate_pid(worker)
                self.assertEqual(run.process.wait(timeout=5), 1)
            finally:
                run.cleanup()

    def test_f_g_wrapper_survives_fifo_eof_then_follows_supervisor(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            state = root / "state"
            config = root / "config.json"
            fifo = root / "uperf.pipe"
            ready = root / "ready"
            log = root / "uperf.log"
            config.write_text(
                json.dumps({"mode": "single", "state_dir": str(state)}), encoding="utf-8"
            )
            script = WRAPPER.read_text(encoding="utf-8")
            replacements = {
                "CONFIG=/data/vendor/zui_control/uperf/uperf.json": f"CONFIG={config}",
                "LOG=/data/vendor/zui_control/log/uperf.log": f"LOG={log}",
                "LOG_PIPE=/data/vendor/zui_control/uperf/.service_log_pipe": f"LOG_PIPE={fifo}",
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
            env["ZUI_UPERF_TEST_BINARY"] = str(DUMMY)
            process = subprocess.Popen(
                ["bash", str(wrapper)],
                env=env,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            daemon = None
            try:
                wait_for(ready.is_file, label="ready marker")
                daemon = wait_for(lambda: read_pid(state / "daemon.pid"), label="daemon pid")
                wait_for(lambda: (state / "writer_closed").is_file(), label="writer close")
                wait_for(lambda: not fifo.exists(), label="FIFO pathname unlink")
                self.assertIsNone(process.poll(), "wrapper exited on FIFO EOF")
                terminate_pid(daemon)
                self.assertEqual(process.wait(timeout=5), 1)
            finally:
                terminate_pid(daemon)
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=2)

    def test_h_production_has_no_polling_or_dummy_fd_leak(self) -> None:
        source = SOURCE.read_text(encoding="utf-8")
        wrapper = WRAPPER.read_text(encoding="utf-8")
        contexts = CONTEXTS.read_text(encoding="utf-8")
        final_verifier = (ROOT / "scripts" / "VerifyZuiControlFlashPackage.ps1").read_text(
            encoding="utf-8"
        )
        self.assertIn("PR_SET_CHILD_SUBREAPER", source)
        self.assertIn("waitpid(-1, &status, 0)", source)
        self.assertIn('9>&- &', wrapper)
        self.assertIn('wait "$supervisor_pid"', wrapper)
        self.assertIn("FIFO EOF", wrapper)
        self.assertIn("'drain_uperf_log <&8 &'", final_verifier)
        self.assertIn("'wait \"$supervisor_pid\"'", final_verifier)
        self.assertNotIn("'while IFS= read -r line <&8; do'", final_verifier)
        self.assertIn(
            "/system/bin/zui_uperf_supervisor u:object_r:performanced_exec:s0", contexts
        )
        for token in ("sleep 5", "pidof uperf", "ps ", "grep ", "cgroup.procs", "while true"):
            self.assertNotIn(token, source)
            self.assertNotIn(token, wrapper)


def main() -> int:
    receipt_dir = None
    if "--receipt-dir" in sys.argv:
        index = sys.argv.index("--receipt-dir")
        receipt_dir = Path(sys.argv[index + 1])
        del sys.argv[index:index + 2]
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(UperfSupervisorTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if receipt_dir is not None:
        receipt_dir.mkdir(parents=True, exist_ok=True)
        summary = {
            "tests_run": result.testsRun,
            "failures": len(result.failures),
            "errors": len(result.errors),
            "skipped": len(result.skipped),
            "old_fifo_lifetime_assumption_fixture": "FAIL_EXPECTED",
            "subreaper_fixture": "PASS" if result.wasSuccessful() else "FAIL",
            "result": "PASS" if result.wasSuccessful() else "FAIL",
        }
        (receipt_dir / "host_fixture_results.json").write_text(
            json.dumps(summary, indent=2) + "\n", encoding="utf-8"
        )
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
