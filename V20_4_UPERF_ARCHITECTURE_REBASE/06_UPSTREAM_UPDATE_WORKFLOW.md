# 06 Monthly upstream update workflow

Run the standard-library-only importer against the new Magisk ZIP:

```powershell
python scripts/build/ImportUperfUpstream.py `
  --zip 'D:\path\to\new-uperf.zip' `
  --output 'V20_4_UPERF_ARCHITECTURE_REBASE\raw\next-upstream-audit'
```

It validates ZIP member safety and emits:

- ZIP filename/size/SHA256 and module version/versionCode;
- upstream and production binary SHA256, byte equality and embedded versions;
- flattened SM8650 config diff;
- relevant script/perapp hashes and diff against the previous frozen snapshot when present;
- lexical owner-conflict report;
- manual adoption-candidate summary.

Output inside the repository is restricted to this work package's `raw/` tree. The tool never
extracts into or edits `payload/`, `framework_patch/`, app, init or policy files. A reviewer must
freeze a new minimal snapshot, classify every field with the adoption matrix, approve a transplant,
then run all host/build/final-artifact gates. `binary changed` or `version newer` is not approval.
