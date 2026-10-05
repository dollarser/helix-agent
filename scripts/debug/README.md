# Dated debugging and test scripts

Save temporary automation here **before running it**, under `YYYY-MM-DD/` (local date).
Keep reusable supported runners at their existing `scripts/` paths. Store logs, APKs,
screenshots, UI dumps and user data only in ignored `build/` directories.

Every emulator run must launch its own process, reject an already connected serial,
and shut down that process in `finally`. Never borrow an emulator started by another
person or agent. A physical device must be selected explicitly; fixture tests must
not run against user data.

Completed, unreferenced one-off editors and inactive source snapshots may be removed
after checking references and preserving a recoverable source identity. The obsolete
September 5–9 script snapshots and retired Goal verifier were removed on 2026-10-05;
see the [cleanup record](../../docs/evidence/development/obsolete-material-cleanup-2026-10-05.md).
Their original code remains in Git history; do not restore them as current runners.

Example, from the repository root with `ANDROID_HOME` set:

```sh
python3 scripts/run-owned-emulator.py \
  --avd YOUR_INACTIVE_AVD --port 5598 \
  --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk \
  --classes com.helix.app.ui.SystemBarInsetsDeviceTest \
  --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
  --output build/debug/2026-09-09/unique-run
python3 scripts/debug/2026-09-09/test-owned-emulator.py
```

The runner freezes both APKs and hashes, checks nonempty JUnit results, and records
process ownership and shutdown. It uses a read-only AVD instance without saved
snapshots; retries create a new process and a new output directory.

For explicitly configured live fixtures, `--reverse-port PORT` forwards only on the
owned emulator, and repeatable `--instrument-arg KEY=VALUE` supplies test arguments.
Each run also captures bounded TestRunner/System.out logcat evidence. Live model
tests use synthetic history and never read the phone's provider or conversations.

HXA-177 one-off implementation scripts in `2026-09-09/` are retained for provenance,
not supported migrations to rerun. `run-owned-emulator.py` is the reusable runner;
`hxa177-*` output folders contain failed and successful frozen attempts separately.

HXA-179/180/181 extraction, wiring, formatting and documentation-edit scripts are
one-off provenance, not repeatable migrations. Use `run-chat-responsibility-regression.sh`
and `run-manual-files-regression.sh` for their device suites, and choose a fresh output
folder. The manual file suite uses `--grant-shared-storage` before instrumentation:
changing that permission from inside a running test can terminate its process.
Failed runs additionally retain synthetic-device logcat and a UI hierarchy dump.

## Stable entry points

Reusable runners now live under `scripts/`; see [supported tools](../README.md).
The former dated runner paths are compatibility shims, not duplicate implementations.
New references should use the stable paths. Retained historical runners remain available;
ignored output directories are not a substitute for preserving reproduction tools.
