#!/usr/bin/env python3
from __future__ import annotations

import json
import os
from pathlib import Path
import signal
import sys
import time


STARTUP_PREFIX = (
    "09:38:27 I Uperf v1.0.6 startup\n"
    "09:38:27 I Loading /data/vendor/zui_control/uperf/uperf.json\n"
    "09:38:28 I Power model initialized\n"
)
READY = "09:38:28 I Uperf is running\n"


def write_state(path: Path, value: str) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(value, encoding="ascii")
    os.replace(temporary, path)


def write_pid(path: Path, pid: int) -> None:
    write_state(path, f"{pid}\n")


def pause_forever() -> None:
    signal.signal(signal.SIGTERM, lambda _signum, _frame: sys.exit(0))
    signal.signal(signal.SIGINT, lambda _signum, _frame: sys.exit(0))
    while True:
        signal.pause()


def replace_with_regular(path: Path, content: str) -> int:
    temporary = path.with_name(path.name + ".new")
    temporary.unlink(missing_ok=True)
    with temporary.open("w", encoding="utf-8") as output:
        output.write(content)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)
    return path.stat().st_ino


def write_startup_log(mode: str, output_path: Path, state: Path) -> None:
    # Match the device contract: Uperf owns -o, unlinks any old pathname, and
    # creates a regular file rather than writing through an inherited FIFO.
    output_path.unlink(missing_ok=True)
    time.sleep(0.06)

    if mode == "replace_ready":
        with output_path.open("w", encoding="utf-8") as output:
            output.write(STARTUP_PREFIX + "09:38:28 I Uperf is run")
            output.flush()
            os.fsync(output.fileno())
        write_state(state / "first_inode", f"{output_path.stat().st_ino}\n")
        time.sleep(0.14)
        inode = replace_with_regular(output_path, STARTUP_PREFIX + READY)
        write_state(state / "final_inode", f"{inode}\n")
        return

    if mode == "truncate_ready":
        with output_path.open("w", encoding="utf-8") as output:
            output.write(STARTUP_PREFIX + ("x" * 2048) + " I Uperf is run")
            output.flush()
            os.fsync(output.fileno())
        first_inode = output_path.stat().st_ino
        time.sleep(0.14)
        with output_path.open("w", encoding="utf-8") as output:
            output.write(STARTUP_PREFIX + READY)
            output.flush()
            os.fsync(output.fileno())
        write_state(state / "truncated", f"inode={first_inode}\n")
        return

    if mode == "split_ready":
        with output_path.open("w", encoding="utf-8") as output:
            output.write(STARTUP_PREFIX + "09:38:28 I Uperf is run")
            output.flush()
            os.fsync(output.fileno())
            write_state(state / "partial_written", "1\n")
            time.sleep(0.14)
            output.write("ning\n")
            output.flush()
            os.fsync(output.fileno())
        return

    if mode == "no_ready":
        replace_with_regular(output_path, STARTUP_PREFIX + "09:38:28 I still starting\n")
        return

    if mode == "failed":
        replace_with_regular(output_path, STARTUP_PREFIX + "09:38:28 I Failed to start uperf\n")
        return

    replace_with_regular(output_path, STARTUP_PREFIX + READY)


def main() -> int:
    if len(sys.argv) != 4 or sys.argv[2] != "-o":
        return 64
    config = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    mode = config["mode"]
    state = Path(config["state_dir"])
    output_path = Path(sys.argv[3])
    state.mkdir(parents=True, exist_ok=True)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    first = os.fork()
    if first > 0:
        return 0
    os.setsid()
    second = os.fork()
    if second > 0:
        os._exit(0)

    write_pid(state / "daemon.pid", os.getpid())
    if mode == "multiple":
        worker = os.fork()
        if worker == 0:
            write_pid(state / "worker.pid", os.getpid())
            pause_forever()
            return 0

    write_startup_log(mode, output_path, state)
    write_state(state / "startup_write_complete", "1\n")
    if mode == "ready_short":
        time.sleep(0.20)
        return 0
    pause_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
