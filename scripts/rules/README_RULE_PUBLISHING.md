# Upstream rules publishing

`PublishRules.py` reuses the qualified static `normalize_source` parser. It writes
only `latest.json` and normalized Schema2 `rules.conf` under the chosen output
directory. Canonical, AppOpt and recovered CSV inputs are supported; imported
scripts/binaries are never executed. Input maximum is 128 KiB, rules maximum
64 KiB, metadata maximum 2 KiB. Production rule source is never edited.
Published upstream mappings must be exact packages, following the existing V83
native library input boundary; valid runtime prefix/contains syntax is not
silently flattened into an upstream library baseline.

```text
python scripts/rules/PublishRules.py --input payload/system/etc/zuiopt/factory_rules.conf --output <outside-source-output-directory> --revision <integer> --source zuiopt-upstream --source-version <upstream-version> --source-date YYYY-MM-DD --source-commit <40-lowercase-hex-source-commit>
```

The revision is an integer in `[1, 9007199254740991]`. Compare numerically, across
GitHub and its mirrors. A lower revision is stale even if its hash differs. The
same revision must have exactly the same metadata; otherwise reject the conflict.
The producer checks the preceding metadata before writing, and publishes the
manifest last. A client racing publication can see a mismatched two-file pair;
it must reject hash/size mismatch and retry, never apply the unverified body.
Content bytes are deterministic for the same source bytes and explicit metadata.
Publication Git commit timestamps are not part of this content contract.

`latest.json` has exactly these fields: `schema=1`, `revision`, `source`,
`sourceVersion`, `sourceDate`, `sourceCommit`, `targetSoC=SM8650`,
`targetTopology=0-7`, `rulesSchema=2`, `rulesSha256`, `rulesSize`.
Dates are valid ISO calendar dates. Source/version/commit fields have bounded
grammars in `PublishRules.py`; booleans cannot substitute for integer fields.

## GitHub producer and Gitee mirror

`.github/workflows/publish-rules.yml` validates on source pushes and offers manual
`workflow_dispatch`. By default a dispatch creates an artifact only. Publishing
requires explicitly choosing `publish=true` on the repository default branch.
After this workflow is merged to that branch, its manual action is available.
Use the selected checkout's checked-in upstream source and explicit upstream
version/date. The Git driver verifies that the source bytes match that checkout's
commit, recorded as `sourceCommit`; it does not infer external upstream provenance.

The driver reads the existing `rules-published` distribution branch and allocates
`previousRevision + 1` (first publication is 1). The job serializes publication;
Git fast-forward rejection also protects against external races. It commits an
output-only tree to that distribution branch, leaving production source HEAD and
its normal index untouched. No generated output belongs on a production branch.
There is no `output/**` push trigger or workflow in the distribution branch.

The canonical URLs, **after Owner enables publishing**, are:

```text
https://raw.githubusercontent.com/<owner>/<repo>/rules-published/output/latest.json
https://raw.githubusercontent.com/<owner>/<repo>/rules-published/output/rules.conf
```

Optional Gitee needs `mirror_gitee=true` together with `publish=true`, repository
variable `GITEE_SSH_URL` (`git@gitee.com:owner/repo.git`), and secrets
`GITEE_DEPLOY_KEY` (a write-capable key scoped to the mirror) and
`GITEE_KNOWN_HOSTS` (independently verified host key entries). Host-key checking is
strict. No credentials or guessed host fingerprints are stored in source.
The step pushes the exact accepted GitHub commit to Gitee's `rules-published`
branch, without force and without any reverse synchronization or Gitee producer.
If this optional step fails, GitHub's publication remains accepted; treat Gitee as
unavailable/stale and retry mirroring that canonical commit. Do not re-label a
Gitee hash difference as a newer revision.

Workflow syntax/permissions and dispatch behavior are defined by the
[GitHub workflow reference](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)
and [manual event documentation](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_dispatch).

## Final Android adapter contract

Networking belongs to the App. User-triggered Check fetches bounded metadata only.
Compare against the last accepted canonical revision and any mirror numerically;
reject same-revision conflicts and wrong platform before advertising an update.
User-triggered Sync downloads at most 64 KiB and verifies exact SHA256, size,
platform and Schema2 grammar. Verify against the metadata downloaded for that
operation; reject/retry a publication race. Hash verification provides integrity
binding, while the configured HTTPS canonical URL is the trusted producer.

Build the existing V83 `ZuioptLibrary.pack` from the verified baseline and
use existing native preview/conflict/apply. Do not create a new pack grammar or
auto-apply downloads. D05 Old/New/Clean three-way choices, generation CAS, current
plus previous rollback, restore App and library export remain unchanged. The
publisher introduces no Android networking, timer, scheduler owner or native
rules authority. Online credentials/hosting activation and final App adapter are
Owner/frontend integration steps, not actions taken by this backend branch.

Validation: `python tests/zuiopt/TestRulePublisher.py [native-fixture]`. This tests
deterministic output, invalid/stale rejection, input preservation, local Git
publication/dry-run, output-only history and native Schema2 parser parity.
