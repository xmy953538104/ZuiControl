# 03 — qdl-rs Read-back Audit

## Result

The previous `--read-back-verify` invocation is `NOT_PROVEN`, not PASS.

The audited binary is qdl-rs 0.1.0 from Qualcomm qdlrs commit
`412f90bc08cc3a687d552ff599da29043c4f54f4`. In [CLI source](https://github.com/qualcomm/qdlrs/blob/412f90bc08cc3a687d552ff599da29043c4f54f4/cli/src/main.rs),
the flag is copied into `FirehoseConfiguration`. In [library source](https://github.com/qualcomm/qdlrs/blob/412f90bc08cc3a687d552ff599da29043c4f54f4/qdl/src/lib.rs),
`firehose_program_storage` serializes it only as the `<program read_back_verify="1">`
attribute, sends program data, and waits for ACK. It does not call the separate
`firehose_read_storage`, receive partition bytes, or compare a host hash.

## Answers

1. **Does the flag implement observable physical read-back?** It requests opaque programmer-side
   behavior, but qdl-rs 0.1.0 does not implement a host-visible physical read/compare in its flasher.
   The target programmer's internal handling and coverage cannot be proved from the host result.
2. **Why were there zero read handlers?** The flasher program path never calls the separate read
   path. Seven program handlers and zero read handlers match the source implementation.
3. **Is this a bad invocation?** No syntax error is evident. It is a semantic/observability gap:
   the help text overstates what the host can prove. RC0 and `All went well!` prove ACKed program
   operations, not returned physical bytes.
4. **How will real read-back be proved?** `FlashZuiControl9008.ps1` now leaves the target in EDL,
   invokes `dump-part` on every fixed-seven label and XML-declared UFS LUN, checks exact byte
   length and SHA-256 against the approved image, writes a seven-entry manifest, and resets to
   system only after `PHYSICAL_PARTITION_READBACK=PASS`.

All seven images exactly fill their programmed partition extent: super 13,958,643,712 bytes;
boot_a/boot_b 100,663,296 bytes each; and each vbmeta target 65,536 bytes. Full read-back is
therefore unambiguous. Large temporary dumps are deleted immediately after hashing, preventing
the old editable-image accumulation problem. The method covers every written partition and makes
no claim about allowlist-external storage.

Implementation audit: [`raw/qdl_source_audit.txt`](raw/qdl_source_audit.txt). Previous failure
fixture: [`raw/qdl_flag_only_fixture.txt`](raw/qdl_flag_only_fixture.txt).
