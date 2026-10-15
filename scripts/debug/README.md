# Dated debugging and test scripts

Save temporary automation here **before running it**, under `YYYY-MM-DD/local/`
(local date, ignored). Reviewed reproduction runners may remain tracked under dated paths.
Keep reusable supported runners at their existing `scripts/` paths. Store logs, APKs,
screenshots, UI dumps and user data only in ignored `build/` directories.

Every emulator run must launch its own process, reject an already connected serial,
and shut down that process in `finally`. Never borrow an emulator started by another
person or agent. A physical device must be selected explicitly; fixture tests must
not run against user data.

Tracking, archival and deletion follow the single [repository retention policy](../../docs/development/README.md#git-跟踪与留存).
The [cleanup record](../../docs/evidence/development/obsolete-material-cleanup-2026-10-05.md)
records removed snapshots and locally archived one-off editors. Do not restore
these as current migration or test entry points.

Example, from the repository root with `ANDROID_HOME` set:

```sh
python3 scripts/run-owned-emulator.py \
  --avd YOUR_INACTIVE_AVD --port 5598 \
  --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk \
  --classes com.helix.app.ui.SystemBarInsetsDeviceTest \
  --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
  --output build/debug/2026-09-09/unique-run
python3 scripts/run-host-tests.py
```

The runner freezes both APKs and hashes, checks nonempty JUnit results, and records
process ownership and shutdown. It uses a read-only AVD instance without saved
snapshots; retries create a new process and a new output directory.
It also checks actual APK manifests before launch: the runner and instrumentation
target must match the frozen pair. Subscription acceptance requires the integrated
Developer App APK; the retired standalone CLI/PRoot Runtime and their library self-test hosts
are rejected as product targets. See the [retirement record](../../docs/evidence/development/runtime-artifact-retirement-2026-10-13.md).

For explicitly configured live fixtures, `--reverse-port PORT` forwards only on the
owned emulator, and repeatable `--instrument-arg KEY=VALUE` supplies test arguments.
Each run also streams filtered TestRunner/System.out logcat evidence from before instrumentation.
The stream retains early events rather than taking only a terminal fixed-size tail. Live model
tests use synthetic history and never read the phone's provider or conversations.

HXA-177 one-off editors are recovered from Git when needed, not supported migrations to rerun. `run-owned-emulator.py` is the reusable runner;
`hxa177-*` output folders contain failed and successful frozen attempts separately.

HXA-179/180/181 editors are retired; their resulting code/tests and history remain. Use `scripts/debug/2026-09-09/run-chat-responsibility-regression.sh`
and `scripts/debug/2026-09-09/run-manual-files-regression.sh` for their device suites, and choose a fresh output
folder. The manual file suite uses `--grant-shared-storage` before instrumentation:
changing that permission from inside a running test can terminate its process.
Failed runs additionally retain synthetic-device logcat and a UI hierarchy dump.

## Stable entry points

Reusable runners now live under `scripts/`; see [supported tools](../README.md).
Redundant dated shims and completed editors are retired with Git/path/hash provenance.
New references should use the stable paths. Retained historical runners remain available;
ignored output directories are not a substitute for preserving reproduction tools.

See the [maintenance playbook](../../docs/development/debugging-playbook.md) and [retirement record](../../docs/evidence/development/test-script-maintenance-2026-10-10.md) for current alternatives and recovery provenance.

Completed version-specific installers, frozen verifiers and local release logs may be retired after owner-authorized curation. Read the [historical version summary](../../docs/evidence/development/historical-version-retirement-2026-10-10.md); historical commands are provenance, not supported current entry points.


## 2026-10-15 curation

Maintained entry points now live in [device_baseline](../device_baseline/README.md),
[fixtures](../fixtures/README.md) and [experiments/ffmpeg](../experiments/ffmpeg/README.md).
Remaining dated files retain task-specific reproduction or unique experimental
value. Date alone is not a deletion criterion. Original source hashes, replacements
and retirement reasons are in the [curation inventory](archive/2026-10-15-script-curation.json).

The 131 pre-existing untracked scripts in the [local inventory](archive/2026-10-15-worktree-inventory.json)
remain at their original paths, preserving their internal calls and recovery bytes.
Their exact paths are excluded in this checkout's `.git/info/exclude`; no wildcard
hides future files and no tracked file is untracked. Seven references to the maintained
baseline were updated and recorded by current hash. Historical source editors retain
their original targets and must not be rerun on current code.
New temporary scripts use dated `local/`, covered by the shared `.gitignore` rule.
