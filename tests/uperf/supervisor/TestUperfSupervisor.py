#!/usr/bin/env python3
"""Compatibility entrypoint for the current Uperf supervisor host gate."""

from pathlib import Path
import subprocess
import sys


TARGET = (
    Path(__file__).resolve().parents[1]
    / "startup"
    / "TestUperfRegularLogReadiness.py"
)
REPEATED_RUNS = 20


if __name__ == "__main__":
    for run in range(1, REPEATED_RUNS + 1):
        print(f"SUPERVISOR_FIXTURE_RUN={run}/{REPEATED_RUNS}", flush=True)
        args = sys.argv[1:]
        if "--receipt-dir" in args:
            index = args.index("--receipt-dir") + 1
            args[index] = str(Path(args[index]) / f"run-{run:02d}")
        subprocess.run([sys.executable, str(TARGET), *args], check=True)
