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
