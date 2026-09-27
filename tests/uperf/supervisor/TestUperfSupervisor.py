#!/usr/bin/env python3
"""Compatibility entrypoint for the current Uperf supervisor host gate."""

from pathlib import Path
import hashlib
import json
import subprocess
import sys


TARGET = (
    Path(__file__).resolve().parents[1]
    / "startup"
    / "TestUperfRegularLogReadiness.py"
)
REPEATED_RUNS = 20


if __name__ == "__main__":
    if "--receipt-dir" in sys.argv:
        receipt = Path(sys.argv[sys.argv.index("--receipt-dir") + 1])
        receipt.mkdir(parents=True, exist_ok=True)
        repo = TARGET.parents[3]
        dependencies = ["native/zui_uperf_supervisor.c", "payload/system/bin/zui_uperf_service",
            "payload/system/etc/zui_control/zui_scheduler_prepare.sh",
            "payload/system/etc/init/zui_scheduler.rc", "payload/system/etc/init/zui_controld.rc",
            "payload/system/etc/zui_control/zui_uperf_crash_gate.sh",
            "payload/system/etc/zui_control/uperf-sm8650.json",
            "framework_patch/src/services/com/zui/server/control/UperfConfigStore.java",
            "framework_patch/src/services/com/zui/server/control/PolicyCommand.java",
            "tests/uperf/startup/TestUperfBootstrap.py",
            "tests/uperf/startup/TestUperfRegularLogReadiness.py",
            "tests/uperf/supervisor/TestUperfSupervisor.py", "tests/fixtures/dummy_uperf.py",
            "tests/uperf/architecture/UperfConfigFixture.java"]
        (receipt / "integration-dependencies.json").write_text(json.dumps({
            "schema": 1, "inheritance": "ALL_INPUT_HASHES_MUST_MATCH;OTHERWISE_RERUN_LINUX_GATE",
            "files": {p: hashlib.sha256((repo / p).read_bytes()).hexdigest() for p in dependencies}
        }, indent=2) + "\n", encoding="utf-8")
    for run in range(1, REPEATED_RUNS + 1):
        print(f"SUPERVISOR_FIXTURE_RUN={run}/{REPEATED_RUNS}", flush=True)
        args = sys.argv[1:]
        if "--receipt-dir" in args:
            index = args.index("--receipt-dir") + 1
            args[index] = str(Path(args[index]) / f"run-{run:02d}")
        subprocess.run([sys.executable, str(TARGET), *args], check=True)
