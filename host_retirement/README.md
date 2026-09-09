# Separate host upgrade utility

This directory is not compiled or copied into the ROM. `inventory` is the default.
Step 2 runs fixtures only; a future authorized device gate must review a fresh
inventory SHA before using `retire --execute`. There is no EDL or flash entry.

Only HA25HSZM / TB321FU / 16.1.11.072 with the five pinned interim payload hashes
is accepted. Root uses `su -c` with an independently checked child-status trailer.
Normal stop/re-enable opens the signed interim App, finds its Threads navigation
from fresh XML and scrolls at most eight times to a unique exact button. Each
scroll/action re-dumps XML; coordinates are never reused after a page rerender.
The wanted button is checked before navigation on every fresh frame. Threads
page state stays set when its header scrolls offscreen. POSIX tasks/cpuset test
fixtures use explicit bytes on Windows too; production numeric parsing stays strict.
Only authenticated UI is used, never private commands or forced termination.

Before product stop, the utility saves exact target bytes, symlink and ownership/
mode plus a binary-safe RuleStore archive. Fresh identity checks precede narrow
removals. Unknown content, links, hardlinks, owners or rule changes fail closed.
Failure attempts same-boot restoration and authenticated re-enable. Directory
allocation size is recorded but not treated as restorable file content.

Rollback is deliberately refused once the approved interim boot is no longer
observable, including after entering EDL/program. Keep all transaction receipts;
`ready_for_flash.json` is superseded by any later rollback receipt. This utility
does not authorize flashing. A later device gate must separately prove its UI,
stop/release and rollback behavior on the device.

The audited current RuleStore needs no provenance conversion. If a future
inventory finds legacy provenance, this tool refuses mutation until an explicit
authenticated Rule Manager conversion workflow is qualified. It never rewrites
private rules directly. The host test proves the provenance-only parser/repack
delta preserves execution using a hash-bound functional-reference parser.

Run `sudo python3 host_retirement/TestRetirement.py` on Linux. Windows runs only
the portable checks; skipped UID/mode rollback fixtures are not device evidence.

## Direct retired-cpuset proof and durable receipts

After product stop, one command (15-second deadline) proves process absent,
init service stopped, and every tasks file in /dev/cpuset/asopt and descendants
empty. Only that hierarchy is enumerated. No unrelated process scan and no
per-thread cat process. A bounded optional survivor set is captured from that
hierarchy before stop and checked by shell-builtin reads of /proc/TID/cpuset;
exited TIDs are allowed, surviving TIDs must be outside the retired owner.
Missing/unreadable task files, unexpected paths/output and timeout fail closed.
Each proof saves its outcome and elapsed milliseconds, including failures.

Every command has an fsynced start receipt BEFORE dispatch (sequence/full argv/
timeout/monotonic start), then result, timeout with partial stdout/stderr, or OS
error receipt. Continuations append after every existing command receipt suffix.
The old missing-timeout-receipt architecture is retired.

The original transaction/UID/mode/unrelated-data fixtures plus TestRepair classes
run through TestRetirement.py. For local retained real-XML regression set
ZUIOPT_REAL_UI_FIXTURES to the prior Gate raw directory; these private device
captures are not committed. CI uses minimal structural equivalents of those
observed UI layouts. On Windows ZUIOPT_TEST_SH may name the existing Git sh.exe
for read-only hierarchy shell fixtures; actual Linux root rollback still runs in CI.

Host-tool commits are NOT a new ROM source. Keep ROM_SOURCE and HOST_TOOL_SOURCE
separate; no newly emitted CI APK/image may replace a previously authorized ROM.
Use a fresh inventory/transaction for each separately authorized retirement.
