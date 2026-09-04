# qdl transfer and progress logging audit

This phase made no device transition and ran no Firehose command. The audit is
based on the retained Golden read-back evidence, the installed qdl binary, and
the pinned upstream source.

## Observed Golden run

| Item | Observation |
| --- | --- |
| Tool | `D:\3.VScode\Mi\flash\Binaries\Qcom\qdl-rs.exe`, `qdl-rs 0.1.0` |
| Backend | Windows help exposes `usb/serial`; Windows uses the serial path by default |
| Golden super read-back | 13.00 GB at approximately 41.42 MB/s |
| Super raw qdl log | 559,254,304 bytes |
| All seven raw qdl logs | 567,355,136 bytes |
| Retained compact super log | 1,030 bytes |
| Current enumeration | no Qualcomm/9008 device; Android device only |

The Golden operation completed successfully and produced an exact SHA-256
match, so the large log is not evidence of a transfer failure. Upstream
`firehose_read_storage()` updates a progress bar for each received buffer. When
captured through a host pipeline/PTY, those redraws become durable lines and
dominate the log. The 559 MB log is therefore a logging-path defect, not a 559
MB protocol payload.

## Performance hypotheses

| Factor | Current evidence | Next safe measurement |
| --- | --- | --- |
| USB link / enumeration | Not measurable while no 9008 device is present | Record PnP instance, driver provider/version and USB topology during the next already-approved EDL session. |
| Windows driver | No current Qualcomm device entry | Compare the same programmer/partition with the bound serial/WinUSB driver recorded. |
| qdl backend | Installed CLI exposes explicit `--backend`; Golden script used the working Windows path | A/B only in a dedicated tooling gate; never during a required production milestone. |
| Firehose payload/request size | Not captured as an authoritative negotiated value | Retain `configure` response and programmer log without enabling verbose per-packet spam. |
| Hash packets | Optional `--hash-packets` is documented as slow | Keep disabled unless packet validation is the purpose of the gate. |
| Read-back verify | Documented as very slow for program operations | Keep required where release policy requires it; do not trade integrity for speed. |
| Progress output | Confirmed 559 MB amplification | Use the host compactor now; propose an upstream one-line/periodic mode separately. |

## Phase 1 correction

`scripts/tools/Convert-ZuiQdlProgressLog.ps1` is a host-only postprocessor. It:

- retains session identity, loader and Firehose logs, errors, NAK/timeouts and
  the final success marker;
- samples transfer progress at 64 MiB by default;
- writes source and compact SHA-256 metadata;
- never deletes the source unless the caller explicitly supplies both
  `-VerifiedOperation` and `-DeleteOriginal` and the source contains the final
  success marker.

The fixture gate proves a 64 MiB sampling interval, error/completion retention,
and default source retention. It is not integrated into the production flash or
read-back scripts in this phase.

## Recommended implementation order

1. Use the host compactor only after the existing operation result and artifact
   hash have been independently accepted.
2. During the next separately approved EDL tooling session, capture driver,
   backend, negotiated Firehose configuration and wall-clock throughput.
3. If modifying qdl-rs, add a native non-TTY compact progress mode (time or byte
   cadence) while preserving stderr and final completion. Validate against the
   current executable before replacing it.
4. Never relax fixed-seven allowlists, read-back verification or hash gates to
   improve headline throughput.

Pinned upstream reference:
<https://github.com/qualcomm/qdlrs/tree/412f90bc08cc3a687d552ff599da29043c4f54f4>

```text
QDL_PROGRESS_LOGGING_PLAN=HOST_COMPACTOR_PASS_NOT_INTEGRATED
```
