#!/usr/bin/env python3
from __future__ import annotations

import json
import os
from pathlib import Path
import signal
import sys


def write_pid(path: Path, pid: int) -> None:
    temporary = path.with_suffix(".tmp")
    temporary.write_text(f"{pid}\n", encoding="ascii")
    os.replace(temporary, path)


def pause_forever() -> None:
    signal.signal(signal.SIGTERM, lambda _signum, _frame: sys.exit(0))
    signal.signal(signal.SIGINT, lambda _signum, _frame: sys.exit(0))
    while True:
        signal.pause()


def main() -> int:
    if len(sys.argv) != 4 or sys.argv[2] != "-o":
        return 64
    config = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    state = Path(config["state_dir"])
    state.mkdir(parents=True, exist_ok=True)

    first = os.fork()
    if first > 0:
        return 0
    os.setsid()
    second = os.fork()
    if second > 0:
        os._exit(0)

    daemon_pid = os.getpid()
    write_pid(state / "daemon.pid", daemon_pid)
    if config["mode"] == "multiple":
        worker = os.fork()
        if worker == 0:
            write_pid(state / "worker.pid", os.getpid())
            pause_forever()
            return 0

    with open(sys.argv[3], "w", encoding="utf-8", buffering=1) as output:
        output.write("fixture I Uperf is running\n")
    (state / "writer_closed").write_text("1\n", encoding="ascii")
    if config["mode"] == "short":
        return 0
    pause_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
