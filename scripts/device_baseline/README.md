# Device baseline tools

These tools run and classify explicitly selected Android test classes. They do not
launch an emulator or grant device authorization. Use the existing owned runner for
process ownership and pass the designated serial explicitly.

- `manifest.py`: discover classes from current source; generated defaults go to ignored `build/device-baseline/`.
- `run.py CLASSES --serial SERIAL --out-dir FRESH_PATH`: execute classes, preserving locks, atomic results and recovery evidence.
- `summary.py OUT_DIR --manifest MANIFEST`: validate and classify collected results.
- `expectations.py`: historical failure signatures and required host phases, separated from parsing and execution.

Historical classifications are not passes; current acceptance still requires all
requested cases to pass. An unrelated working directory is supported by each CLI's
`--help` and the default source discovery. Explicit relative input/output arguments
remain relative to the caller's working directory.

For an isolated installation, pass `--application-id PACKAGE` to `run.py`; the
instrumentation package is `PACKAGE.test`. Preparation for each class and recovery
may stop and restart the selected processes, but never clear app data. Individual test fixtures can still
modify their target app: review their cleanup scope before using a daily installation.
The runner exits zero only for a non-empty complete set of actual passes; failed,
skipped, missing and unresolved checks remain in the report and return nonzero.

The package replaces the three dated Python entry points and positional shell shim
from `scripts/debug/2026-09-26/`. Recover historical invocation sources using the
[script curation inventory](../debug/archive/2026-10-15-script-curation.json).

## Independent R8 UI checks

`scripts/run-release-smoke.py` uses the separate `tests/release-smoke` host to inspect a
non-debuggable Release target through public Android UI APIs. Build an isolated,
test-signed target with `scripts/release-smoke.init.gradle` and
`-PincludeReleaseSmoke=true -Phelix.smokeApplicationId=com.helix.validation.releasecheck`.
See the [candidate verification record](../../docs/evidence/development/pre-refactor-finish-2026-10-15.md)
for the bounded coverage and remaining execution checks.

Pass the explicitly authorized device serial, target application ID, target APK,
independent test APK and a new evidence directory. The default `--suite basic` checks
navigation and installed version on either channel. `--suite mobile-use` requires a
Developer target and checks configured app scope, conversation selection, process
restart and manual deselection. It temporarily enables only that isolated target's
Accessibility service, records the original settings, then removes its service and
packages while retaining other enabled services. Neither suite calls a model or
clears an existing installation; an already installed target is rejected.

`--suite mobile-use-execution --boundary cancel|unknown|stale` additionally requires
an explicitly authorized emulator and a Developer target. An independent loopback
provider emits deterministic requests through the real Dispatcher; all effects
stay in the disposable test Activity. The test creates provider, plugin scope and
conversation permissions through public UI. It requires emulator `su 0 sqlite3`
for read-only queries of the isolated target's durable facts, and never writes SQL
or reads daily-installation databases. Cases cover stopping an executing wait,
terminating the target process during an actual Android gesture and retaining its
unresolved effect, and rejecting a pre-restart node token before using a fresh
observation. Android force-stop can leave Accessibility disconnected; the stale
case reconnects only the isolated target's service without changing conversation
selection or scope. Each run
removes its packages and restores Accessibility settings. These fixtures do not
measure real-model quality, real-phone/OEM behavior or the entire lifecycle matrix.

## Fixed real-task batch

The frozen four-task definition is `evals/real-tasks/manifest.json`. It reuses the
existing real-model device tests, not the Memory/index ablation. `real_tasks.py`
only summarizes synthetic evidence; it never logs in, reads provider secrets,
executes a task or retries a failure. A JUnit pass or `Turn COMPLETED` is not a
business pass. Fixture source-verification assertions set `completed`/`taskPassed`.
Automatic test approvals are recorded separately from human interventions; these
are controlled fixture results, not claims of unattended real-world reliability.
Old evidence without these counters stays unknown (`null`), never zero.

Live file, browser and cross-session fixtures refresh the current built-in catalog
and wait for the selected candidate model projection before submitting. A normal
PendingConfirmation is confirmed through `confirmSubmission` with the original
request identity only for the owned synthetic session, built-in provider, HTTPS
api.hilex.me origin, no attachments and no high-sensitivity categories. It grants
no tool permissions. Disclosure confirmations increment `fixtureApprovals` and
receipt types/safe reason codes remain in `submissionReceipts`, including failed
catalog/projection stages. Other receipt outcomes are not retried or coerced into
acceptance. Global Memory must be empty; fixtures reject rather than clear it.
Keep pre-fix failed batches and collect any repaired-candidate attempt in a fresh
batch; a repaired run is not the original attempt.


Check service and sign-in readiness for each new candidate. Historical outages or
region failures are not evidence of a fresh execution. If the existing account or
service is unavailable, record the affected cases as `BLOCKED`, attempt 0, with
sanitized current dependency evidence. They are neither executed failures nor
passes. Resume only after the dependency changes, using a new batch directory.
Never inspect credentials, replace the service or change authentication to make a
batch pass. New sign-in and credential transmission require the applicable approval.

### Daily-installation subset

On an already authenticated `com.helix.agent.developer` daily installation,
explicitly select only the file-location, browser or cross-session methods allowed
by their existing guards. The browser case additionally requires
`browserSearchDailyAcceptance=true`. Retain `helix.preserveUserSettings=true` and
serialize each device run with `scripts/with-host-slot.py --`. Do not run the
complete four-method list against the daily package: natural log diagnosis still
requires `com.helix.agent.developer.asrvalidation` with its own normally authorized
sign-in. Never transfer credentials/tokens or relax that package guard.

If a current real request establishes a shared service dependency failure, stop
subsequent dependent tasks and record them as unexecuted `BLOCKED` with that fresh
evidence. A fixture/admission failure without a wire request is not a service or
region failure. Keep login state, prior failed batches and all fixture approval
counts. Collect newly created synthetic case directories, including original
`cleanup-result.json` when admission failed before `result.json` existed; never
rename or synthesize a passing evidence artifact.

### Run exactly once after the existing account is available

Prerequisites: a verified, explicitly selected emulator serial, the successful
Developer APK and matching AndroidTest APK already installed as
`com.helix.agent.developer.asrvalidation`, an existing signed-in model account,
empty global Memory, no active turn/draft or conflicting `session` fixture. The
baseline runner does not install, launch an emulator, configure permissions or
prepare credentials. Preserve APK hashes and source diff with the candidate's
build evidence. All four tests use the candidate's `BuiltInModel` identity; per-case model overrides
are forbidden. Freeze the manifest from the same public source used to build the
installed candidate, preserving its source hash beside the build receipt. The
summarizer requires every persisted model call's provider/model identity to match
that frozen identity; missing identities, mixed models and successful zero-call
records fail closed. Identity extraction includes only public provider/model fields,
never credentials or the full provider snapshot.

From the repository root, set `SERIAL` to the verified value, and `BATCH` to a
**new** path under `build/`. Execute each method once, retaining failures. Crash
recovery only probes health, never retries a task. Instrumentation arguments are
non-secret flags, not a channel for credentials.

```sh
set -eu
mkdir "$BATCH" # must fail if it already exists
python3 scripts/device_baseline/real_tasks.py \
  --freeze-manifest evals/real-tasks/manifest.json --output "$BATCH/manifest.json"
python3 - "$BATCH" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
m = json.loads((root / 'manifest.json').read_text())
assert all(c['test'] for c in m['cases']), 'All four test entries must be wired'
(root / 'classes.txt').write_text('\n'.join(c['test'] for c in m['cases']) + '\n')
PY
adb -s "$SERIAL" shell run-as com.helix.agent.developer.asrvalidation \
  find cache -name result.json > "$BATCH/before.txt"
run_status=0
python3 scripts/device_baseline/run.py "$BATCH/classes.txt" \
  --serial "$SERIAL" --flavor developer \
  --application-id com.helix.agent.developer.asrvalidation \
  --out-dir "$BATCH/instrumentation" --timeout 420 \
  --instrument-arg helix.preserveUserSettings=true \
  --instrument-arg fileLocationModelAcceptance=true \
  --instrument-arg naturalDiscovery=true \
  --instrument-arg browserSearchModelAcceptance=true \
  --instrument-arg helixCrossSessionModel=true || run_status=$?
printf '%s\n' "$run_status" > "$BATCH/instrumentation-exit-code.txt"
# Preserve nonzero results; do not rerun failed methods.
adb -s "$SERIAL" shell run-as com.helix.agent.developer.asrvalidation \
  find cache -name result.json > "$BATCH/after.txt"
```

Compare before/after and collect **only newly created synthetic case directories**
matching each manifest's evidence path. Use an explicit run path, never “latest”
or a success-selected previous run. For each such directory:

```sh
# CASE_DIR is the exact new cache-relative directory, not account/database paths.
adb -s "$SERIAL" exec-out run-as com.helix.agent.developer.asrvalidation \
  tar -cf - "$CASE_DIR" > "$BATCH/CASE_ID.tar"
```

Unpack the synthetic evidence into that batch and write `batch.json` in this form
(all evidence paths relative to the batch; preserve the original files):

```json
{
  "manifest": "manifest.json",
  "manifestSha256": "SHA256_OF_THE_FROZEN_MANIFEST_BYTES",
  "cases": [
    {"id": "file-location", "attempt": 1, "evidence": "cache/file-location-model/RUN/result.json"},
    {"id": "log-diagnosis", "attempt": 1, "evidence": "cache/natural-failure-tasks/diagnosis-RUN/result.json"},
    {"id": "web-citation", "attempt": 1, "evidence": "cache/browser-search-model/RUN/result.json"},
    {"id": "cross-session-recall", "attempt": 0, "status": "BLOCKED",
     "attribution": {"category": "AUTH", "reason": "Same configured account unavailable before execution",
                     "evidence": ["sanitized-auth-result.json"]}}
  ]
}
```

The example illustrates a blocked item, not a claim about this run. Use observed
facts for all four. Missing items stay `NOT_RUN`, included in the planned task
count. Do not synthesize a success record when setup failed before `result.json`.
Use a blocked record with actual setup evidence, or leave it unobserved. Attribution
may be AUTH, SERVICE, HARNESS, CONTEXT, TOOL, MODEL or UNKNOWN. Except for explicit
HTTP 401/403, classification needs review: attach the reason and supporting
artifacts rather than interpreting every incomplete answer as a model defect.
A MODEL attribution requires checking that the intended context, authorized tools,
saved results and subsequent model consumption were actually available.

```sh
python3 scripts/device_baseline/real_tasks.py "$BATCH/batch.json" \
  --output "$BATCH/task-report.json"
```

The report preserves the planned denominator, task-level status, request/tool
counts, elapsed milliseconds, human/fixture interventions and failure attribution.
`modelCalls` counts persisted logical provider calls and is required; `wireRequests`
counts observed transport attempts, is optional and stays null if not captured.
They are not interchangeable (transport retries can increase the latter).
Missing required measurements keep the report incomplete. It deliberately does not compute
a model-quality score from a mixture of infrastructure and model outcomes. Both
summary tools concern this batch only, never whole-product acceptance.
