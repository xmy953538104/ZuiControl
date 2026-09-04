# Firehose device-side digest feasibility

## Decision

```text
FIREHOSE_DIGEST_STATUS=NOT_APPROVED
```

No Firehose digest command was sent in this phase. The device was not in 9008,
the installed `qdl-rs 0.1.0` CLI exposes no digest subcommand, and the current
programmer has not been proven to return a digest value that the host can
compare. Entering EDL solely for this experiment would violate the phase gate.

The pinned qdl-rs library contains an internal
`firehose_checksum_storage()` path that issues `getsha256digest`. At the pinned
revision it checks for an ACK but does not expose or validate the programmer's
digest value, and its timeout handling is marked incomplete. This is useful
implementation evidence, but it is not a trustworthy release verification
contract.

Source:
<https://github.com/qualcomm/qdlrs/blob/412f90bc08cc3a687d552ff599da29043c4f54f4/qdl/src/lib.rs>

## Safe future A/B protocol

Run only during a separately approved EDL tooling gate using the already proven
programmer and an allowlisted small, read-only partition.

1. Record device serial, programmer SHA-256, qdl build/source revision, storage
   type, LUN, sector size and exact start/length.
2. Full-dump the small partition and compute host SHA-256 using the existing
   trusted path.
3. Request the Firehose digest for the identical byte range and retain the raw
   XML response.
4. Require an explicit 64-hex digest, ACK, exact range echo where available, and
   equality with the host full-dump SHA-256.
5. Repeat across reconnects and both a small data partition and a metadata
   partition. Any missing digest, ambiguous range, timeout or mismatch is FAIL.
6. Do not perform erase/program/reset beyond the already-approved session
   lifecycle.

Even after an A/B PASS, device digest may become an optimization for routine
checks only after independent review. Milestone fixed-seven full physical
read-back remains mandatory until policy explicitly changes.
