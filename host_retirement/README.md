# Separate host upgrade utility

This directory is not compiled or copied into the ROM. `inventory` is the default.
Step 2 runs fixtures only; a future authorized device gate must review a fresh
inventory SHA before using `retire --execute`. There is no EDL or flash entry.

Only HA25HSZM / TB321FU / 16.1.11.072 with the five pinned interim payload hashes
is accepted. Root uses `su -c` with an independently checked child-status trailer.
The user must expose the signed interim App's Threads page; normal stop/re-enable
uses its authenticated UI, never private commands or forced process termination.

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
