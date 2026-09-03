#!/usr/bin/env python3
"""Compatibility entrypoint for the current Uperf supervisor host gate."""

from pathlib import Path
import runpy


TARGET = (
    Path(__file__).resolve().parents[2]
    / "V20_4_UPERF_REGULAR_LOG_READINESS_CORRECTION"
    / "tests"
    / "TestUperfRegularLogReadiness.py"
)


if __name__ == "__main__":
    runpy.run_path(str(TARGET), run_name="__main__")
