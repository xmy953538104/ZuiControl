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
        subprocess.run([sys.executable, str(TARGET), *sys.argv[1:]], check=True)
