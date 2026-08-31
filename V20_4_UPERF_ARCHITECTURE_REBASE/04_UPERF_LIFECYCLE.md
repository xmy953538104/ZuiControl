# 04 Uperf lifecycle

## Existing problem

The current shell wrapper starts a daemonizing binary, performs up to 20 startup checks, then
wakes every five seconds to count cgroup processes and grep the log twice. The loop provides
health detection and turns a missing worker into a wrapper exit so init restarts the cgroup. It
does not perform placement after startup and is not needed for singleton enforcement.

Historical worker-fault testing proved normal 10/10 recovery but also proved the failure mode:
the polling wrapper could exit repeatedly while Uperf's own daemon manager was already restarting
its worker, causing Android init to reach `sys.init.updatable_crashing=1`.

## Final design

1. The wrapper creates a private FIFO in the labeled Uperf data directory.
2. Uperf writes its normal log stream to that FIFO; the same wrapper appends it to `uperf.log`.
3. Startup readiness is a single bounded `read -t` phase. There is no recurring timeout after
   readiness.
4. The wrapper then blocks in `read`. Log arrival and EOF are kernel events; idle causes no wakeup,
   fork, grep or process scan.
5. Uperf's built-in SIGCHLD/wait manager remains responsible for a single worker exit.
6. Three `terminated unexpectedly` events within 20 seconds set a fail-safe property. Init then
   explicitly stops `zui_uperf` instead of allowing an unbounded internal storm.
7. If the entire Uperf writer tree exits, FIFO EOF makes the wrapper exit and ordinary init restart
   semantics recover it.
8. An `onrestart` one-shot crash gate handles wrapper/whole-tree startup storms. It counts only
   services that die within two seconds of readiness; three consecutive rapid deaths set the same
   fail-safe. A service that lived longer resets the rapid counter.

There is one init-owned wrapper, not an added daemon, watchdog or periodic timer. Uperf remains in
the service cgroup, so `stop zui_uperf` kills the wrapper and detached Uperf descendants. Explicit
stop does not run `onrestart`; fail-safe and counters are cleared only by an explicit scheduler
start/restart or the next boot preparation.

## Expected states

| Event | Result |
|---|---|
| one worker crash | Uperf internal event-driven restart; init service remains running |
| whole Uperf tree EOF | wrapper exits; init restarts after its normal rate limit |
| three worker crashes / 20 s | `uperfFailSafe=1`, service stopped/degraded |
| three sub-2 s service deaths | same degraded state before generic init crash threshold |
| scheduler explicit stop | remains stopped, no recovery action |
| scheduler explicit restart | clear fail-safe/counters, prepare config, start one instance |

Device testing must still prove FIFO inheritance, log completeness, exact stop behavior and the
rapid bounds. Host tests can prove the absence of a periodic loop and the static init/property
contract, not runtime timing.
