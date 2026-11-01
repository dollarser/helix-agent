---
name: helix-acceptance-closeout
description: "Close out a Helix candidate or review whether its acceptance evidence supports delivery. Use for release preparation, scoped validation, artifact handoff, or acceptance gaps; not for routine edits that do not need candidate acceptance."
---

# Helix Acceptance Closeout

Use the ongoing task and its compaction summary; this skill does not choose work from the repository queue or grant device, account, deletion or publication authority. Project boundaries remain in [AGENTS.md](../../../AGENTS.md).

## Relevant acceptance evidence

- Separate inherited unchanged-baseline evidence, intentionally extended paths and newly executed checks; an old pass is not a fresh full run. Keep source/artifact hashes in ignored receipts, not this skill. Bind successful build inputs, APK identity and installed bytes. A failed build's leftover APK is not a candidate; reuse unchanged evidence with its applicable boundary.
- Choose checks for the requested outcome and affected paths. Separate host tests, test APK compilation, device effects, durable results and actual model consumption; a wrapper or Turn completion alone is insufficient.
- Apply the current task and [AGENTS device rules](../../../AGENTS.md#verification-and-device-authorization): daily-emulator cover-install, additional device validation, cleanup and sensitive grants have distinct authority. Carry valid ongoing-task authorization across handoff; do not routinely ask again. Repository guidance cannot waive platform confirmations.
- Preserve unrelated data and parallel edits. For pre-launch Helix test data, use the current AGENTS data policy rather than adding old-format compatibility; lack of a preservation requirement does not authorize unnecessary clearing. Verify original task/target identity before installation, cleanup or recovery. Working directory is an execution location, not the full authorized scope.
- Keep failures, skips and untested conditions visible. Fix scoped defects without weakening boundary assertions. Missing authority or destructive installation blocks that action, not independent work.

Existing commands are in the [verification reference](../../../docs/development/verification-matrix.md) and [debugging reference](../../../docs/development/debugging-playbook.md): `check-all.sh`, `build-release-apks.sh`, owned emulator runners and `instrumentation_junit.py` / `summarize-owned-runs.py`. Use what fits the task, without a duplicate runner or fixed test-count convention. [Release readiness](../../../docs/development/release-readiness.md) applies to explicitly authorized distribution.

Report concise implementation/verification results, source and candidate identity, remaining conditions and cleanup. Keep raw evidence in ignored `build/<task>/`, temporary helpers in `scripts/local/debug/YYYY-MM-DD/`, and stable conclusions in the relevant task/evidence record. `not requested` and `pending` are not passes.
